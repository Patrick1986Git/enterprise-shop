#!/usr/bin/env python3
"""Collect reviewed Maven classpaths, validate them, and emit an ephemeral CycloneDX BOM."""
import argparse
import base64
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path
from urllib.parse import quote, unquote, urlparse

ROOT = Path(__file__).resolve().parent.parent
CONTRACT = ROOT / '.github/security/build-tool-inventory.json'
IDENTITY = 'enterprise-shop-build-tools'
DISTRIBUTION_PACKAGE = 'org.apache.maven:apache-maven:pom::3.10.0'
REQUIRED_PROCESSORS = {'org.mapstruct:mapstruct-processor:jar::1.6.3',
                       'org.hibernate.orm:hibernate-processor:jar::7.4.11.Final'}
TOKEN = re.compile(r'^[A-Za-z0-9_.+\-]+$')
SHA256 = re.compile(r'^[0-9a-f]{64}$')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def coordinate(values):
    require(len(values) == 5, 'Malformed Maven coordinate')
    g, a, t, c, v = values
    require(all(TOKEN.fullmatch(x) for x in (g, a, t, v))
            and (not c or TOKEN.fullmatch(c)), 'Invalid/unresolved Maven coordinate')
    return ':'.join(values)


def purl(value):
    g, a, t, c, v = value.split(':')
    require(t == 'jar' or value == DISTRIBUTION_PACKAGE, 'Unreviewed executable artifact extension')
    result = f'pkg:maven/{quote(g, safe="")}/{quote(a, safe="")}@{quote(v, safe="")}'
    if c:
        result += f'?classifier={quote(c, safe="")}&type=jar'
    elif t == 'pom':
        result += '?type=pom'
    return result


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def read_json(path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, 'Duplicate JSON key')
            result[key] = value
        return result
    return json.loads(Path(path).read_text(encoding='utf-8'), object_pairs_hook=unique)


def write_json(path, value):
    Path(path).write_text(json.dumps(value, sort_keys=True, indent=2) + '\n', encoding='utf-8')


def evidence(path, mode):
    lines = Path(path).read_text(encoding='utf-8').splitlines()
    require(len(lines) > 3 and lines[0] == f'begin\t1\t{mode}', 'Missing evidence header')
    rows = [line.split('\t') for line in lines]
    require(rows[-1] == ['end', '1', str(len(rows) - 1)], 'Truncated/failed Maven evidence')
    lengths = {'begin': 3, 'artifact': 7, 'model-plugin': 4, 'configured': 2,
               'configured-artifact': 8, 'configured-url': 3, 'executed': 4,
               'actual': 2, 'actual-artifact': 8, 'actual-url': 3,
               'processor-config': 3, 'session': 2, 'end': 3}
    require(all(x[0] in lengths and len(x) == lengths[x[0]] for x in rows),
            'Malformed Maven event record')
    require([x for x in rows if x[0] == 'session'] == [['session', '0']],
            'Maven session did not succeed')
    artifacts = {}
    for row in rows:
        if row[0] == 'artifact':
            key = str(Path(row[-1]).resolve(strict=True))
            value = coordinate(row[1:6])
            require(key not in artifacts or artifacts[key] == value, 'Ambiguous artifact provenance')
            artifacts[key] = value
    require(artifacts, 'Empty resolution evidence')
    return rows, artifacts


def realm_graphs(rows, artifacts, kind):
    graphs = {}
    for row in rows:
        if row[0] == kind:
            graphs.setdefault(row[1], {'resolved': set(), 'realm': set()})
        elif row[0] == kind + '-artifact':
            require(row[1] in graphs, 'Realm artifact has no root')
            path = str(Path(row[-1]).resolve(strict=True))
            value = coordinate(row[2:7])
            require(artifacts.get(path) == value, 'Realm artifact lacks resolver identity')
            graphs[row[1]]['resolved'].add(value)
        elif row[0] == kind + '-url':
            require(row[1] in graphs, 'Realm URL has no root')
            url = urlparse(row[2])
            require(url.scheme == 'file' and not url.netloc, 'Unreviewed plugin classloader URL')
            path = str(Path(unquote(url.path)).resolve(strict=True))
            require(path in artifacts, 'Classloader URL lacks resolver identity')
            graphs[row[1]]['realm'].add(artifacts[path])
    require(graphs, 'No plugin realms')
    for root, graph in graphs.items():
        require(graph['realm'] and graph['realm'] <= graph['resolved'], 'Partial realm evidence')
        g, a, v = root.split(':')
        require(coordinate((g, a, 'jar', '', v)) in graph['realm'], 'Plugin root absent from realm')
    return graphs


def bootstrap(maven_home, reviewed):
    home = Path(maven_home)
    paths = {p.relative_to(home).as_posix(): p for folder in ('boot', 'lib')
             for p in (home / folder).rglob('*.jar')}
    require(paths.keys() == reviewed.keys() and paths, 'Changed/partial Maven distribution')
    result = set()
    for relative, path in paths.items():
        item = reviewed[relative]
        require(SHA256.fullmatch(item['sha256']) and digest(path) == item['sha256'],
                'Maven distribution artifact byte mismatch')
        value = coordinate(item['coordinate'].split(':'))
        with zipfile.ZipFile(path) as archive:
            metadata = [n for n in archive.namelist()
                        if n.startswith('META-INF/maven/') and n.endswith('/pom.properties')]
            require(len(metadata) <= 1, 'Ambiguous Maven distribution metadata')
            if metadata:
                fields = dict(line.split('=', 1) for line in archive.read(metadata[0]).decode().splitlines()
                              if '=' in line and not line.startswith('#'))
                g, a, _, _, v = value.split(':')
                require((fields.get('groupId'), fields.get('artifactId'), fields.get('version'))
                        == (g, a, v), 'Distribution metadata disagrees with reviewed identity')
            else:
                require(item['authority'] == 'distribution-pom-resolution-and-byte-match',
                        'Unknown distribution artifact provenance')
        result.add(value)
    return result


def processor_roots(rows):
    result = {}
    for row in rows:
        if row[0] != 'processor-config':
            continue
        require(row[1] not in result, 'Duplicate compiler processor evidence')
        root = ET.fromstring(base64.b64decode(row[2], validate=True))
        require(root.tag == 'annotationProcessorPaths' and len(root), 'Malformed processor configuration')
        values = set()
        for path in root:
            require(path.tag == 'path', 'Unknown processor configuration entry')
            value = coordinate((path.findtext('groupId', ''), path.findtext('artifactId', ''),
                                path.findtext('type', 'jar'), path.findtext('classifier', ''),
                                path.findtext('version', '')))
            require(value not in values, 'Duplicate processor root')
            values.add(value)
        result[row[1]] = values
    require(result.keys() == {'compile', 'testCompile'}, 'Missing compiler executions')
    require(result['compile'] == result['testCompile']
            and REQUIRED_PROCESSORS <= result['compile'], 'Required processor absent')
    return result['compile']


def execution_paths(log, artifacts, project_root):
    lines = Path(log).read_text(encoding='utf-8').splitlines()
    require(sum('[INFO] BUILD SUCCESS' in x for x in lines) == 1, 'Missing successful execution log')
    roots = {str((Path(project_root) / 'target' / x).resolve()) for x in ('classes', 'test-classes')}

    def identify(paths, directories=False):
        result = set()
        require(paths and all(paths), 'Empty execution classpath')
        for value in paths:
            path = str(Path(value).resolve(strict=True))
            if directories and path in roots:
                continue
            require(path in artifacts, 'Execution path lacks authoritative resolver identity')
            require(artifacts[path].split(':')[2] == 'jar', 'Non-JAR executable requires review')
            result.add(artifacts[path])
        return result

    processor_paths = []
    for line in lines:
        match = re.search(r' -processorpath (\S+)', line)
        if match:
            paths = match[1].split(':')
            if paths[-1] == '':  # Maven's compiler emits one trailing separator.
                paths.pop()
            processor_paths.append(identify(paths))
    require(len(processor_paths) == 2 and processor_paths[0] == processor_paths[1],
            'Incomplete/changed compiler processor path')

    def classpaths(label):
        return [identify(x.split(label, 1)[1].split(), directories=True)
                for x in lines if label in x]

    providers = classpaths('provider classpath:')
    booters = classpaths('boot classpath:')
    tests = classpaths('test classpath:')
    require(len(providers) == len(booters) == len(tests) == 2, 'Incomplete dynamic provider evidence')
    require(sum('Using auto detected provider org.apache.maven.surefire.junitplatform.JUnitPlatformProvider'
                in x for x in lines) == 2, 'Unreviewed test provider selection')
    forks = [x for x in lines if 'Forking command line:' in x]
    require(len(forks) == 2, 'Unreviewed test JVM fork topology')
    agents = []
    for line in forks:
        paths = re.findall(r'-javaagent:([^\s\'"=]+\.jar)', line)
        agents.append(identify(paths))
    require(agents[0] == agents[1], 'Test JVM agent mismatch')
    return {'processors': sorted(processor_paths[0]),
            'providers': {'surefire': sorted(providers[0]), 'failsafe': sorted(providers[1])},
            'booters': {'surefire': sorted(booters[0] - tests[0]),
                        'failsafe': sorted(booters[1] - tests[1])}, 'agents': sorted(agents[0])}


def collect(directory, contract):
    directory = Path(directory)
    verify, verify_artifacts = evidence(directory / 'verify.tsv', 'verify')
    docker, docker_artifacts = evidence(directory / 'docker.tsv', 'docker')
    configured = realm_graphs(verify, verify_artifacts, 'configured')
    require(configured == realm_graphs(docker, docker_artifacts, 'configured'),
            'Effective plugin resolution differs between hosted and Docker commands')
    model_roots = {':'.join(x[1:]) for x in verify if x[0] == 'model-plugin'}
    require(model_roots == configured.keys(), 'Effective plugin missing from inventory')
    actual = realm_graphs(verify, verify_artifacts, 'actual')
    for root, graph in actual.items():
        require(configured.get(root) == graph, 'Executing realm differs from effective resolution')
    direct = realm_graphs(docker, docker_artifacts, 'actual')
    dependency = 'org.apache.maven.plugins:maven-dependency-plugin:3.10.0'
    require(direct.keys() == {dependency}, 'Unexpected/missing Docker direct plugin')
    require('commons-beanutils:commons-beanutils:jar::1.11.0' in direct[dependency]['realm']
            and 'commons-beanutils:commons-beanutils:jar::1.11.0'
            in configured['org.apache.maven.plugins:maven-site-plugin:3.22.0']['realm'],
            'BeanUtils remediation missing from required realms')
    graphs = configured | direct
    distribution = bootstrap((directory / 'maven-home.txt').read_text().strip(), contract['distribution'])
    require(contract['distribution_package']['coordinate'] == DISTRIBUTION_PACKAGE
            and contract['distribution_package']['source'] ==
            'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.10.0/apache-maven-3.10.0.pom'
            and SHA256.fullmatch(contract['distribution_package']['sha256']),
            'Unreviewed Maven distribution package authority')
    distribution_ga = {':'.join(x.split(':')[:2]) for x in distribution}
    components = set(distribution)
    components.add(DISTRIBUTION_PACKAGE)
    for graph in graphs.values():
        require(all(':'.join(x.split(':')[:2]) in distribution_ga
                    for x in graph['resolved'] - graph['realm']),
                'Filtered plugin dependency has no reviewed Maven API authority')
        components.update(graph['realm'])
    paths = execution_paths(directory / 'verify.log', verify_artifacts, ROOT)
    roots = processor_roots(verify)
    require(roots <= set(paths['processors']), 'Processor root missing from actual compiler path')
    components.update(paths['processors'])
    components.update(paths['agents'])
    for kind in ('providers', 'booters'):
        for members in paths[kind].values():
            components.update(members)
    require(not any(x.startswith('commons-beanutils:commons-beanutils:') and x.endswith(':1.9.4')
                    for x in components), 'BeanUtils 1.9.4 is forbidden')
    all_artifacts = verify_artifacts | docker_artifacts
    by_coordinate = {}
    for path, value in all_artifacts.items():
        if value not in components:
            continue
        checksum = digest(path)
        require(value not in by_coordinate or by_coordinate[value] == checksum,
                'Same coordinate resolved to different artifact bytes')
        by_coordinate[value] = checksum
    inventory = {'model_plugins': sorted(model_roots),
                 'plugins': {k: {s: sorted(v) for s, v in g.items()} for k, g in sorted(graphs.items())},
                 'processor_roots': sorted(roots), **paths,
                 'executions': {mode: sorted({':'.join(x[1:]) for x in rows if x[0] == 'executed'})
                                for mode, rows in [('verify', verify), ('docker', docker)]},
                 'components': sorted(components)}
    return inventory


def validate_inventory(inventory, contract):
    require(inventory == contract['inventory'], 'Build-tool graph changed; review the inventory contract')


def bom(inventory):
    components = []
    for value in inventory['components']:
        g, a, _, _, v = value.split(':')
        identity = purl(value)
        components.append({'type': 'application' if value == DISTRIBUTION_PACKAGE else 'library',
                           'bom-ref': identity, 'group': g,
                           'name': a, 'version': v, 'purl': identity})
    return {'bomFormat': 'CycloneDX', 'specVersion': '1.5', 'version': 1,
            'metadata': {'component': {'type': 'application', 'bom-ref': IDENTITY,
                                      'name': IDENTITY, 'version': '1'}},
            'components': components}


def validate_bom(document, contract):
    # The owned subset is stricter than CycloneDX: no unknown fields, timestamps,
    # inferred dependencies, duplicate identities, or missing reviewed components.
    require(isinstance(document, dict) and type(document.get('version')) is int,
            'Malformed CycloneDX document/version')
    require(document == bom(contract['inventory']), 'Malformed/incomplete/non-deterministic build-tool CycloneDX')
    identities = [x['purl'] for x in document['components']]
    require(identities and len(set(identities)) == len(identities), 'Duplicate/empty CycloneDX identities')


def prepare(directory):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    for name in ('extensions.xml', 'maven.config', 'jvm.config'):
        require(not (ROOT / '.mvn' / name).exists(), 'Unreviewed Maven bootstrap configuration')
    text = (directory / 'maven-version.txt').read_text()
    require(re.search(r'^Apache Maven 3\.10\.0 ', text, re.M), 'Unreviewed Maven version')
    require(re.search(r'^Java version: 21\.', text, re.M), 'Java 21 is required')
    match = re.search(r'^Maven home: (.+)$', text, re.M)
    require(match, 'Maven Wrapper did not provide distribution location')
    home = Path(match[1])
    (directory / 'maven-home.txt').write_text(str(home) + '\n')
    classes = directory / 'collector-classes'
    classes.mkdir(exist_ok=True)
    classpath = os.pathsep.join(str(home / folder / '*') for folder in ('lib', 'boot'))
    with tempfile.TemporaryDirectory(prefix='build-tool-source-') as temporary:
        source = Path(temporary) / 'BuildToolEvidence.java'
        shutil.copyfile(ROOT / 'scripts/build-tool-inventory/BuildToolEvidence.java.source', source)
        subprocess.run(['javac', '--release', '21', '-Xlint:all', '-Werror', '-cp', classpath,
                        '-d', str(classes), str(source)], check=True)
    target = classes / 'META-INF/plexus/components.xml'
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(ROOT / 'scripts/build-tool-inventory/components.xml', target)
    subprocess.run(['jar', '--create', '--file', str(directory / 'collector.jar'), '-C', str(classes), '.'], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['prepare', 'collect', 'validate-bom'])
    parser.add_argument('--directory', default='.tmp/build-tool-security')
    parser.add_argument('--contract', default=str(CONTRACT))
    parser.add_argument('--review-contract', help='Write a candidate contract for explicit reviewer inspection; never used by CI')
    parser.add_argument('--bom', default='.tmp/build-tool-security/enterprise-shop-build-tools.cdx.json')
    args = parser.parse_args()
    try:
        if args.command == 'prepare':
            prepare(args.directory)
        else:
            contract = read_json(args.contract)
            if args.command == 'validate-bom':
                validate_bom(read_json(args.bom), contract)
            else:
                # A failed recollection must never leave a previously scan-ready document.
                Path(args.bom).unlink(missing_ok=True)
                (Path(args.directory) / 'inventory.json').unlink(missing_ok=True)
                inventory = collect(args.directory, contract)
                if args.review_contract:
                    write_json(args.review_contract, {**contract, 'inventory': inventory})
                    print('Candidate inventory written for review; no scan-ready SBOM emitted.')
                    return 0
                validate_inventory(inventory, contract)
                document = bom(inventory)
                validate_bom(document, contract)
                write_json(Path(args.directory) / 'inventory.json', inventory)
                write_json(args.bom, document)
                print(f'Validated {len(inventory["components"])} build-tool Maven components.')
        return 0
    except (OSError, ValueError, KeyError, TypeError, ET.ParseError, subprocess.CalledProcessError,
            zipfile.BadZipFile) as error:
        print(f'Build-tool inventory failed: {error}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
