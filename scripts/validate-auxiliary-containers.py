#!/usr/bin/env python3
"""Reviewed CI image census and fail-closed auxiliary advisory evidence policy."""
import argparse
import hashlib
import importlib.util
import json
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BINARY_SPEC = importlib.util.spec_from_file_location('auxiliary_binary_evidence', ROOT / 'scripts/auxiliary_binary_evidence.py')
BINARY = importlib.util.module_from_spec(BINARY_SPEC)
BINARY_SPEC.loader.exec_module(BINARY)
CANDIDATE_SPEC = importlib.util.spec_from_file_location('ryuk_candidate_evidence', ROOT / 'scripts/ryuk_candidate_evidence.py')
CANDIDATE = importlib.util.module_from_spec(CANDIDATE_SPEC)
CANDIDATE_SPEC.loader.exec_module(CANDIDATE)
CONTRACT = ROOT / '.github/security/auxiliary-container-scope.json'
BLOCKING = {'HIGH', 'CRITICAL'}
DIGEST = re.compile(r'^sha256:[0-9a-f]{64}$')
IMAGE = re.compile(r'^(docker\.io|ghcr\.io)/[^\s@]+:[^\s@]+@sha256:[0-9a-f]{64}$')
SURFACE = re.compile(r'docker\s+(?:run|create|pull|build|compose)|[\x27\"]docker[\x27\"]|DockerImageName|(?:Generic|PostgreSQL)Container')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read(path):
    return json.loads(path.read_text())


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n')


def execution_sources(root):
    # Whole-source receipts deliberately reject changes to dynamic commands too.
    # This is a reviewed-source census, not a complete interpreter of shell/Java.
    paths = set(root.glob('.github/workflows/*')) | set(root.glob('.github/actions/**/action.y*ml'))
    paths |= set(root.glob('scripts/*.sh')) | set(root.glob('scripts/*.py'))
    paths |= set(root.glob('src/test/**/*.java'))
    paths |= set(root.glob('**/Dockerfile')) - set(root.glob('target/**/Dockerfile'))
    paths |= set(root.glob('*compose*.y*ml'))
    return {p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(paths) if p.is_file()
            and not any(part in {'.git', '.tmp', 'target', '__pycache__'} for part in p.relative_to(root).parts) and
            ('.github/' in p.relative_to(root).as_posix() or p.name == 'Dockerfile'
             or 'compose' in p.name or p.name == 'ryuk_candidate_evidence.py' or SURFACE.search(p.read_text()))}


def inventory(contract, root=ROOT):
    require(contract['schema_version'] == 3 and contract['exceptions'] == [] and contract.get('applicability', []) == [],
            'No auxiliary exception or foreign policy is authorized')
    require(contract['execution_sources'] == execution_sources(root),
            'CI container execution source changed: review image census/classification and source receipts')
    images = contract['images']
    require(isinstance(images, dict) and images, 'Missing reviewed images')
    workflow = (root / '.github/workflows/ci.yml').read_text()
    refs = dict(re.findall(r'^      ([A-Z_]+_IMAGE): (\S+)$', workflow, re.M))
    require(refs == {v['environment']: v['reference'] for v in images.values() if v.get('environment')},
            'Workflow image references differ from reviewed auxiliary inventory')
    require(contract['scanner_independent_integrity'] == 'upstream-github-attestation-and-executable-byte-match',
            'Self-scan cannot establish independent scanner integrity')
    require(contract['scanner_version'] == '0.75.0', 'Unreviewed scanner version')
    tool = contract['tools']['upx']
    require(tool['version'] == '5.2.1' and tool['platform'] == 'linux/amd64'
            and tool['classification'] == 'isolated-evidence-transformer' and tool['exceptions'] == []
            and tool['archive_sha256'] == '402162aad30af47e60dbd767fb2e64ca394ace9727ba1f40283641f1d1b91657'
            and tool['binary_sha256'] == '287b3dffe9dcafd8e366e162ac4ab41e5cf45a3c6768970256af0869288d84a1',
            'Unreviewed evidence transformation tool')
    for name, image in images.items():
        if image.get('candidate_build'):
            require(name == 'ryuk', 'Rebuilt candidate authority is restricted to Ryuk')
            CANDIDATE.inventory(image, read(root / '.github/security/ryuk/candidate.json'))
        else:
            require(IMAGE.fullmatch(image['reference']), f'{name}: readable tag and immutable registry digest required')
        require(image['platform'] == 'linux/amd64', f'{name}: linux/amd64 required')
        require(image['classification'] in {'repository-processing', 'high-impact', 'scanner', 'fixture'},
                f'{name}: explicit reviewed capability classification required')
        require(image['version'] and image['identity'] and image['command'] and image['update_authority']
                and image['advisory_authority'], f'{name}: executable/version/ownership evidence required')
        require(image['governed'] == (image['classification'] != 'fixture'), 'Fixture cannot become trusted evidence')
        require(image['threshold'] == (['HIGH', 'CRITICAL'] if image['governed'] else []), 'Wrong threshold')
        require(image.get('exceptions', []) == [] and image.get('applicability', []) == [], 'No image exception is authorized')
        require((image['package_mode'] == 'static-haskell') == (name == 'hadolint'),
                'Only the reviewed Hadolint scratch executable has an empty static package boundary')
        if image.get('evidence_transform'):
            transform = image['evidence_transform']
            require(image['governed'] and image['classification'] != 'fixture' and image['package_mode'] == 'gobinary'
                    and re.fullmatch(r'/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]+', image['identity']['binary'])
                    and transform['method'] == 'upx-deterministic-go-readback'
                    and transform['tool'] == tool and transform['analysis_image'] == images['govulncheck']['reference']
                    and image['required_gobinaries'] == [image['identity']['binary'].lstrip('/')],
                    'Unreviewed/fixture compressed binary path')
    require(images['fixture']['classification'] == 'fixture' and not images['fixture']['governed'],
            'The policy fixture cannot become a trusted execution image')
    if images['ryuk'].get('candidate_build'):
        comparison = contract['comparisons']['official_ryuk']
        require(comparison['reference'].startswith('docker.io/testcontainers/ryuk:0.14.0@sha256:')
                and comparison['evidence_transform']['method'] == 'upx-deterministic-go-readback'
                and comparison['exceptions'] == [], 'Official compressed comparison must remain available')
        for source in [workflow, (root / '.github/workflows/codeql.yml').read_text()]:
            require('python scripts/build-ryuk-candidate.py' in source, 'Build exact candidate before Testcontainers')
    ryuk = images['ryuk']['reference'].removeprefix('docker.io/')
    require((root / 'src/test/resources/testcontainers.properties').read_text().strip()
            == 'ryuk.container.image=' + ryuk, 'Testcontainers must execute the inventoried Ryuk digest')
    require(images['scanner']['reference'] in (root / 'scripts/scan-build-tools.py').read_text(),
            'Build-tool and image scanner authority must agree')
    job = workflow.split('  container-security:\n', 1)[1].split('\n  deploy-pages:', 1)[0]
    require('    - cron: "23 4 * * 1"' in workflow and '  pull_request:' in workflow
            and '  contents: read\n' in workflow and 'pull_request_target' not in workflow
            and '${{ secrets.' not in job and 'persist-credentials: false' in job
            and "ref: ${{ github.event_name == 'schedule' && 'master' || github.event.pull_request.head.sha || github.ref }}" in job,
            'Auxiliary coverage must remain weekly protected-master and read-only exact PR head')
    for command in ('python scripts/validate-auxiliary-containers.py inventory',
                    'python scripts/scan-auxiliary-containers.py',
                    'python scripts/verify-trivy-provenance.py',
                    'python scripts/validate-auxiliary-containers.py evidence',
                    "python -m unittest discover -s scripts/tests -p 'test_auxiliary_containers.py'"):
        require(command in job, 'Required auxiliary CI control disappeared: ' + command)
    require("python -m unittest discover -s scripts/tests -p 'test_auxiliary_compressed_go.py'" in job,
            'Compressed executable regression control disappeared')
    require('name: auxiliary-container-evidence' in job and 'retention-days: 14' in job
            and job.index('name: Upload auxiliary container evidence') < job.index('name: Enforce auxiliary HIGH and CRITICAL policy'),
            'Retain unfiltered evidence for 14 days before enforcing policy')
    return images


def package_version(package):
    version = package.get('Version', '')
    if package.get('Release'):
        version += '-' + package['Release']
    if package.get('Epoch'):
        version = str(package['Epoch']) + ':' + version
    return version


def validate(image, evidence, report, bom, scanner_version, directory=None):
    require(image['governed'] and image['classification'] != 'fixture', 'Fixture is not trusted execution evidence')
    require(image.get('exceptions', []) == [] and image.get('applicability', []) == [],
            'No auxiliary applicability decision is authorized')
    require(evidence['reference'] == image['reference'] and evidence['platform'] == image['platform'],
            'Image source/platform identity mismatch')
    require(DIGEST.fullmatch(evidence['image_id']) and DIGEST.fullmatch(evidence['resolved_digest']),
            'Missing resolved linux/amd64 image identity')
    require(evidence['identity'] == image['identity'], 'Expected executable/version identity is missing')
    require(all(type(evidence[x]) is int and evidence[x] == 0 for x in
                ('database_status', 'scanner_status', 'sbom_status')), 'Scanner/database/SBOM failure')
    require(evidence['independent_scanner_integrity'] is False, 'Self-scan cannot be independent integrity proof')
    database = evidence['database']
    now = datetime.now(timezone.utc)
    collected = datetime.fromisoformat(evidence['timestamp'].replace('Z', '+00:00'))
    require(collected <= now and (now - collected).total_seconds() < 86400,
            'Missing/stale image evidence timestamp')
    require(database['Version'] == 2 and datetime.fromisoformat(database['UpdatedAt'].replace('Z', '+00:00')) <= now
            and datetime.fromisoformat(database['NextUpdate'].replace('Z', '+00:00')) > now,
            'Malformed/stale database provenance')
    transformed = bool(image.get('evidence_transform'))
    candidate = bool(image.get('candidate_build'))
    if candidate:
        require(directory is not None, 'Candidate build files required')
        head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
        main_module, receipt = CANDIDATE.validate(image, evidence, report, directory, head)
    else:
        main_module, receipt = BINARY.validate(image, evidence, report, bom, directory) if transformed else (None, None)
    require(report['SchemaVersion'] == 2
            and report['ArtifactType'] == ('filesystem' if transformed else 'container_image')
            and report['ArtifactName'] == evidence['artifact_name']
            and report['Trivy']['Version'] == scanner_version
            and (transformed or report['Metadata']['ImageID'] == evidence['image_id']), 'Malformed/wrong raw image evidence')
    require(bom['bomFormat'] == 'CycloneDX' and bom['specVersion'] == '1.7'
            and bom['metadata']['component']['name'] == evidence['artifact_name'], 'Malformed/wrong SBOM identity')
    properties = bom['metadata']['component'].get('properties', [])
    require([p['value'] for p in properties if p['name'] == 'aquasecurity:trivy:ImageID']
            == ([] if transformed else [evidence['image_id']]),
            'SBOM/image identity mismatch')
    results = report.get('Results', [])
    require(isinstance(results, list), 'Malformed raw results')
    components = bom.get('components', [])
    require(isinstance(components, list), 'Malformed SBOM components')
    observed_os = {}
    raw_purls = set()
    targets = {}
    blocked = []
    for result in results:
        packages = result.get('Packages')
        require(isinstance(packages, list) and packages, 'Missing package readback')
        targets[result['Target']] = packages
        identities = {(p['Name'], package_version(p)) for p in packages}
        for package in packages:
            # An unversioned root module is valid only when the measured binary explicitly embeds (devel).
            require(package['Name'] and (package.get('Version') or
                    ((transformed or candidate) and package['Name'] == main_module and package.get('Relationship') == 'root'))
                    and package['Identifier']['PURL'], 'Malformed package identity')
            raw_purls.add(package['Identifier']['PURL'])
        if result['Class'] == 'os-pkgs':
            require(not observed_os, 'Ambiguous OS inventory')
            observed_os = dict(identities)
        vulnerabilities = result.get('Vulnerabilities')
        if vulnerabilities is None:
            vulnerabilities = []
        require(isinstance(vulnerabilities, list), 'Malformed findings')
        for vulnerability in vulnerabilities:
            severity = vulnerability['Severity']
            require(severity in {'UNKNOWN', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL'}
                    and vulnerability['VulnerabilityID']
                    and (vulnerability['PkgName'], vulnerability['InstalledVersion']) in identities,
                    'Malformed/uninventoried vulnerability')
            if severity in BLOCKING:
                blocked.append({'target': result['Target'], 'id': vulnerability['VulnerabilityID'],
                                'package': vulnerability['PkgName'], 'version': vulnerability['InstalledVersion'],
                                'severity': severity})
    require(observed_os == evidence['installed_os_packages'], 'Missing/changed installed OS package readback')
    require(raw_purls == {p['purl'] for p in components if 'purl' in p}, 'Raw report/SBOM package mismatch')
    for target in image.get('required_gobinaries', []):
        require(target in targets and any(p['Name'] == 'stdlib' for p in targets[target]),
                'Missing executable package readback: ' + target)
        if image.get('go_version'):
            require(any(p['Name'] == 'stdlib' and p['Version'] == 'v' + image['go_version'] for p in targets[target]),
                    'Wrong observed Go toolchain')
    if image['package_mode'] == 'static-haskell':
        require(not results and not components and not evidence['installed_os_packages'],
                'Reviewed scratch image must contain only the unrepresented static executable')
    else:
        require(raw_purls, 'Missing package readback cannot be a clean image result')
    return {'reference': image['reference'], 'platform': image['platform'], 'image_id': evidence['image_id'],
            'resolved_digest': evidence['resolved_digest'], 'blocked': blocked, 'exceptions': [],
            'threshold': ['HIGH', 'CRITICAL'], 'package_count': len(raw_purls),
            'independent_scanner_integrity': False, 'package_mode': image['package_mode'],
            'readback_complete': True, 'transformation': receipt}


def evaluate(directory):
    contract = read(CONTRACT)
    images = inventory(contract)
    source = (directory / 'source-sha.txt').read_text().strip()
    require(source == subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
            'Stale source evidence')
    provenance = read(directory / 'scanner-provenance/result.json')
    require(provenance['verified'] is True and provenance['version'] == contract['scanner_version']
            and provenance['image'] == images['scanner']['reference']
            and provenance['method'] == contract['scanner_independent_integrity'], 'Missing independent scanner provenance')
    results = {'source_sha': source, 'images': {}, 'errors': {}, 'scanner_integrity': provenance,
               'fixture': {'reference': images['fixture']['reference'], 'governed': False}}
    for name, image in images.items():
        if not image['governed']:
            continue
        try:
            item = directory / name
            results['images'][name] = validate(image, read(item / 'identity.json'), read(item / 'raw.json'),
                                              read(item / 'sbom.cdx.json'), contract['scanner_version'], item)
        except (OSError, ValueError, KeyError, TypeError, AttributeError) as error:
            results['errors'][name] = str(error)
    if contract.get('comparisons'):
        try:
            item = directory / 'official-ryuk-comparison'
            image = contract['comparisons']['official_ryuk']
            BINARY.validate(image, read(item / 'identity.json'), read(item / 'raw.json'), read(item / 'sbom.cdx.json'), item)
            findings = [v for r in read(item / 'raw.json')['Results'] for v in r.get('Vulnerabilities', [])]
            results['official_ryuk_comparison'] = {'reference': image['reference'], 'execution_authority': False,
                'readback_complete': True, 'findings': len(findings),
                'high_critical': sum(v['Severity'] in BLOCKING for v in findings)}
        except (OSError, ValueError, KeyError, TypeError, AttributeError) as error:
            results['errors']['official-ryuk-comparison'] = str(error)
    write(directory / 'policy-result.json', results)
    print(json.dumps(results, indent=2))
    return 1 if results['errors'] or any(v['blocked'] for v in results['images'].values()) else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('inventory', 'evidence'))
    parser.add_argument('--directory', type=Path, default=ROOT / '.tmp/container-security/auxiliary')
    args = parser.parse_args()
    try:
        if args.command == 'inventory':
            print(json.dumps(inventory(read(CONTRACT)), indent=2))
            return 0
        return evaluate(args.directory)
    except (OSError, ValueError, KeyError, TypeError, AttributeError) as error:
        print('Invalid auxiliary container evidence: ' + str(error), file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
