#!/usr/bin/env python3
"""Fail closed on unreviewed root Dockerfile security boundaries."""

import argparse
import hashlib
import json
import math
import re
import sys
from pathlib import Path
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parents[1]
REVIEWED_STAGES = [
    ('eclipse-temurin:21-jdk-jammy', 'builder'),
    ('eclipse-temurin:21-jre-jammy', 'runtime'),
]


def validate_stages(contents):
    stages = []
    for line in contents.splitlines():
        if re.match(r'^\s*FROM\b', line, re.IGNORECASE):
            match = re.fullmatch(r'\s*FROM\s+(\S+)\s+AS\s+(\S+)\s*', line, re.IGNORECASE)
            if not match:
                raise ValueError('Unreviewed Dockerfile FROM instruction')
            stages.append((match[1], match[2]))
    if stages != REVIEWED_STAGES:
        raise ValueError('Root Dockerfile stages require an explicit builder security policy review')


DIRECTORY = ROOT / '.tmp/container-security/builder'
VDR_REPOSITORY = 'adoptium/temurin-vdr-generator'
BLOCKING = {'HIGH', 'CRITICAL'}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read_json(path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, 'Duplicate JSON key')
            result[key] = value
        return result
    return json.loads(Path(path).read_text(), object_pairs_hook=unique)


def download(url):
    with urlopen(Request(url, headers={'User-Agent': 'enterprise-shop-builder-security'}), timeout=60) as response:
        return response.read()


def fetch_jdk(directory):
    directory.mkdir(parents=True, exist_ok=True)
    for name in ('temurin-vdr.json', 'jdk-advisory-provenance.json', 'policy-result.json'):
        (directory / name).unlink(missing_ok=True)
    release = json.loads(download(f'https://api.github.com/repos/{VDR_REPOSITORY}/releases/latest'))
    tag = release['tag_name']
    require(re.fullmatch(r'temurin-vdr-\d{2}-\d{2}-\d{4}-\d+', tag), 'Unexpected Temurin VDR release')
    require(not release['draft'] and not release['prerelease'], 'Unpublished Temurin VDR')
    assets = {x['name']: x for x in release['assets']}
    name = tag + '.json'
    prefix = f'https://github.com/{VDR_REPOSITORY}/releases/download/{tag}/'
    require(assets[name]['browser_download_url'] == prefix + name
            and assets[tag + '.sha256']['browser_download_url'] == prefix + tag + '.sha256',
            'Unexpected Temurin VDR asset authority')
    payload = download(prefix + name)
    checksum = download(prefix + tag + '.sha256')
    expected = checksum.decode().strip().split()
    require(len(expected) == 2 and expected[1].lstrip('*') == name
            and re.fullmatch(r'[0-9a-f]{64}', expected[0]), 'Malformed vendor checksum manifest')
    digest = hashlib.sha256(payload).hexdigest()
    require(digest == expected[0], 'Temurin VDR checksum mismatch')
    asset_digest = assets[name].get('digest')
    require(asset_digest is None or asset_digest == 'sha256:' + digest, 'GitHub asset digest mismatch')
    (directory / 'temurin-vdr.json').write_bytes(payload)
    (directory / 'temurin-vdr.sha256').write_bytes(checksum)
    provenance = {'repository': VDR_REPOSITORY, 'tag': tag, 'release_id': release['id'],
                  'asset_id': assets[name]['id'], 'url': prefix + name,
                  'published_at': release['published_at'], 'sha256': digest,
                  'upstream_immutable': release.get('immutable', False)}
    (directory / 'jdk-advisory-provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
    print(json.dumps(provenance))


def java_version(release):
    fields = dict(re.findall(r'^([A-Z_]+)="([^"]*)"$', release, re.MULTILINE))
    require(fields.get('IMPLEMENTOR') == 'Eclipse Adoptium', 'Unreviewed JDK vendor')
    version = fields.get('JAVA_VERSION', '')
    require(re.fullmatch(r'21(?:\.\d+){1,3}', version), 'Malformed/non-Java-21 Temurin version')
    return version


def numeric_version(value):
    require(re.fullmatch(r'\d+(?:\.\d+){0,3}', value), 'Unsupported OpenJDK affected version')
    values = tuple(int(x) for x in value.split('.'))
    return values + (0,) * (4 - len(values))


def jdk_findings(vdr, version):
    require(vdr.get('bomFormat') == 'CycloneDX' and vdr.get('specVersion') == '1.4'
            and vdr['metadata']['component']['name'] == 'Eclipse Temurin', 'Malformed vendor VDR')
    vulnerabilities = vdr.get('vulnerabilities')
    require(isinstance(vulnerabilities, list) and vulnerabilities, 'Missing vendor JDK advisories')
    current = numeric_version(version)
    findings = []
    for vulnerability in vulnerabilities:
        identity = vulnerability['id']
        require(re.fullmatch(r'CVE-\d{4}-\d+', identity), 'Malformed JDK advisory identity')
        affects = vulnerability['affects']
        require(isinstance(affects, list) and affects, 'Missing JDK applicability evidence')
        applicable = False
        for affected in affects:
            require(affected['ref'] == 'pkg:github/openjdk/jdk', 'Unreviewed JDK advisory target')
            ranges = affected.get('versions')
            require(isinstance(ranges, list) and ranges, f'Missing affected versions: {identity}')
            for item in ranges:
                # Adoptium's generator encodes OJVG's "affected versions ... and earlier"
                # as generic upper bounds, partitioned by feature version, not exact-only matches.
                require(item.get('status', 'affected') == 'affected'
                        and item['range'].startswith('vers:generic/'), 'Unsupported vendor version semantics')
                for bound in item['range'].removeprefix('vers:generic/').split('|'):
                    if re.fullmatch(r'[78]u\d+', bound):
                        continue
                    maximum = numeric_version(bound)
                    if maximum[0] == current[0] and current <= maximum:
                        applicable = True
        ratings = vulnerability['ratings']
        require(isinstance(ratings, list) and ratings, 'Missing JDK advisory severity')
        scores = [x['score'] for x in ratings if x.get('method') in ('CVSSv3', 'CVSSv31', 'CVSSv4')]
        require(scores and all(type(x) in (int, float) and math.isfinite(x) and 0 <= x <= 10
                               for x in scores), 'Malformed JDK advisory score')
        severity = 'CRITICAL' if max(scores) >= 9 else 'HIGH' if max(scores) >= 7 else 'NON_BLOCKING'
        if applicable and severity in BLOCKING:
            findings.append({'authority': 'Temurin VDR', 'id': identity,
                             'package': 'Eclipse Temurin JDK', 'version': version, 'severity': severity})
    return findings


def dpkg_inventory(text):
    packages = {}
    for line in text.splitlines():
        name, version, architecture, status = line.split('\t')
        if status != 'installed':
            continue
        require(name and version and architecture in ('amd64', 'all') and name not in packages,
                'Malformed/duplicate builder dpkg identity')
        packages[name] = version
    require(packages and 'unzip' in packages, 'Installed builder unzip evidence is missing')
    return packages


def validate_image(report, bom, packages, image_id, scanner_status, sbom_status, database_status):
    require(type(scanner_status) is int and scanner_status == 0
            and type(sbom_status) is int and sbom_status == 0
            and type(database_status) is int and database_status == 0,
            'Scanner/SBOM/database failure cannot be a clean result')
    require(report['SchemaVersion'] == 2 and report['ArtifactType'] == 'container_image'
            and report['Trivy']['Version'] == '0.72.0', 'Unexpected Trivy image provenance')
    metadata = report['Metadata']
    require(metadata['ImageID'] == image_id and metadata['OS']['Family'] == 'ubuntu'
            and metadata['OS']['Name'] == '22.04', 'Wrong builder image/OS identity')
    results = report['Results']
    require(isinstance(results, list) and results, 'Missing builder raw results')
    os_results = [x for x in results if x['Class'] == 'os-pkgs' and x['Type'] == 'ubuntu']
    require(len(os_results) == 1, 'Missing/ambiguous builder OS inventory')
    observed = {x['Name']: x['Version'] for x in os_results[0]['Packages']}
    require(observed == packages, 'Trivy OS inventory differs from installed builder dpkg packages')
    require(bom['bomFormat'] == 'CycloneDX' and bom['specVersion'] in ('1.5', '1.6')
            and bom['metadata']['component']['name'] == 'enterprise-shop/builder:ci',
            'Malformed/wrong builder CycloneDX identity')
    components = bom['components']
    require(isinstance(components, list) and components, 'Missing builder SBOM components')
    os_components = {x['name']: x['version'] for x in components
                     if x.get('purl', '').startswith('pkg:deb/ubuntu/')}
    require(os_components == packages, 'Builder CycloneDX omits/changes installed OS packages, including unzip')
    blocked = []
    component_count = 0
    for result in results:
        contents = result.get('Packages', [])
        require(isinstance(contents, list), 'Malformed raw package evidence')
        component_count += len(contents)
        identities = {(x['Name'], x['Version']) for x in contents}
        vulnerabilities = result.get('Vulnerabilities') or []
        require(isinstance(vulnerabilities, list), 'Malformed raw vulnerability evidence')
        for vulnerability in vulnerabilities:
            severity = vulnerability['Severity']
            require(severity in {'UNKNOWN', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL'}
                    and vulnerability['VulnerabilityID']
                    and (vulnerability['PkgName'], vulnerability['InstalledVersion']) in identities,
                    'Malformed/uninventoried vulnerability')
            if severity in BLOCKING:
                blocked.append({'authority': 'Trivy', 'target': result['Target'],
                                'id': vulnerability['VulnerabilityID'], 'package': vulnerability['PkgName'],
                                'version': vulnerability['InstalledVersion'], 'severity': severity})
    return {'raw_package_count': component_count, 'dpkg_package_count': len(packages),
            'sbom_component_count': len(components), 'unzip': packages['unzip'], 'blocked': blocked}


def evaluate(directory):
    output = directory / 'policy-result.json'
    output.unlink(missing_ok=True)
    validate_stages((ROOT / 'Dockerfile').read_text())
    report = read_json(directory / 'trivy-raw.json')
    bom = read_json(directory / 'builder.cdx.json')
    builder = read_json(directory / 'builder-image.json')[0]
    require(builder['Os'] == 'linux' and builder['Architecture'] == 'amd64', 'Wrong builder platform')
    source = (directory / 'source-sha.txt').read_text().strip()
    require(re.fullmatch(r'[0-9a-f]{40}', source), 'Missing exact source identity')
    packages = dpkg_inventory((directory / 'builder-dpkg.tsv').read_text())
    result = validate_image(report, bom, packages, builder['Id'],
                            int((directory / 'scanner-exit-status.txt').read_text()),
                            int((directory / 'sbom-exit-status.txt').read_text()),
                            int((directory / 'database-exit-status.txt').read_text()))
    version = java_version((directory / 'builder-jdk-release.txt').read_text())
    require((directory / 'builder-javac.txt').read_text().strip() == 'javac ' + version,
            'Builder javac differs from JDK release')
    vdr = read_json(directory / 'temurin-vdr.json')
    provenance = read_json(directory / 'jdk-advisory-provenance.json')
    require(provenance['repository'] == VDR_REPOSITORY and provenance['sha256'] ==
            hashlib.sha256((directory / 'temurin-vdr.json').read_bytes()).hexdigest(), 'Stale/tampered JDK evidence')
    result['blocked'] += jdk_findings(vdr, version)
    result.update({'source_sha': source, 'builder_image_id': builder['Id'], 'jdk_version': version,
                   'runtime_java_version': java_version((directory / 'app-jdk-release.txt').read_text()),
                   'jdk_advisory_provenance': provenance, 'threshold': sorted(BLOCKING),
                   'exceptions': []})
    runtime_packages = {line.split('\t')[0] for line in (directory / 'app-dpkg.tsv').read_text().splitlines()
                        if line.endswith('\tinstalled')}
    result['builder_only_os_packages'] = sorted(packages.keys() - runtime_packages)
    builder_files = set((directory / 'builder-jdk-files.txt').read_text().splitlines())
    runtime_files = set((directory / 'app-jdk-files.txt').read_text().splitlines())
    result['builder_only_jdk_files'] = sorted(builder_files - runtime_files)
    output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))
    return 1 if result['blocked'] else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('stages', 'fetch-jdk', 'evidence'))
    parser.add_argument('--directory', type=Path, default=DIRECTORY)
    args = parser.parse_args()
    try:
        if args.command == 'stages':
            validate_stages((ROOT / 'Dockerfile').read_text())
        elif args.command == 'fetch-jdk':
            fetch_jdk(args.directory)
        else:
            return evaluate(args.directory)
        return 0
    except (OSError, ValueError, KeyError, TypeError, AttributeError, IndexError) as error:
        print(f'Invalid builder security evidence: {error}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
