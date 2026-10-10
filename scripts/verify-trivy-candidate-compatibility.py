#!/usr/bin/env python3
"""Compare isolated scanner commands against pinned positive/negative fixtures."""
import argparse
import hashlib
import io
import importlib.util
import json
import os
import shutil
import subprocess
import tarfile
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SPEC = importlib.util.spec_from_file_location('trivy_evidence', ROOT / 'scripts/trivy_candidate_evidence.py')
EVIDENCE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(EVIDENCE)
REFERENCE = 'ghcr.io/aquasecurity/trivy:0.75.0@sha256:af6acf9a6b85dfe389a1941505c0ce9efef52a4719635e1a962f022a3d855daa'
REFERENCE_BINARY = '93f9da8e4ba5e0c1c76d8234ed2494cf9afb0a96fd21953e424bb795f3299b8e'
EXPECTED = {('CVE-2026-78669', 'golang.org/x/net', 'v0.59.0', 'HIGH'),
            ('CVE-2026-78667', 'stdlib', 'v1.27.1', 'HIGH'),
            ('CVE-2026-78669', 'stdlib', 'v1.27.1', 'HIGH'),
            ('CVE-2026-97031', 'stdlib', 'v1.27.1', 'HIGH')}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def read(path):
    return json.loads(path.read_text())


def write(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def findings(report, blocking=False):
    return {(v['VulnerabilityID'], v['PkgName'], v['InstalledVersion'], v['Severity'])
            for r in report.get('Results', []) for v in r.get('Vulnerabilities', [])
            if not blocking or v['Severity'] in {'HIGH', 'CRITICAL'}}


def packages(report):
    require(report.get('SchemaVersion') == 2 and report.get('Trivy', {}).get('Version') == '0.75.0',
            'Wrong raw schema/scanner version')
    values = {p['Identifier']['PURL'] for r in report.get('Results', []) for p in r['Packages']}
    require(values, 'Missing raw package inventory')
    return values


def database(path):
    value = read(path)
    now = datetime.now(timezone.utc)
    updated, next_update, downloaded = (datetime.fromisoformat(value[k].replace('Z', '+00:00'))
                                       for k in ('UpdatedAt', 'NextUpdate', 'DownloadedAt'))
    require(value['Version'] == 2 and updated <= downloaded <= now < next_update
            and (now - updated).total_seconds() < 172800
            and (next_update - updated).total_seconds() <= 172800,
            'Malformed/stale vulnerability database')
    return value


def collect(build, directory, actual_images=()):
    require(not directory.exists(), 'Compatibility directory already exists')
    directory.mkdir(parents=True)
    receipt = read(build / 'build-receipt.json')
    ref = receipt['image_reference']
    require(receipt['binary_sha256'] == receipt['repeat_sha256'] and receipt['go_version'] == '1.27.2'
            and receipt['repository_head'] == subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
            'Wrong build evidence before compatibility execution')
    inputs = directory / 'inputs'
    inputs.mkdir()
    for name in ('vulnerable', 'patched', 'malformed'):
        shutil.copyfile(ROOT / f'scripts/tests/fixtures/build-tool-sbom/{name}.cdx.json', inputs / f'{name}.cdx.json')
    shutil.copyfile(build / 'image.tar', inputs / 'candidate.tar')
    with tarfile.open(build / 'image.tar') as saved:
        manifest = json.load(saved.extractfile('manifest.json'))
        with tarfile.open(fileobj=saved.extractfile(manifest[0]['Layers'][0])) as layer:
            binary = layer.extractfile('usr/local/bin/trivy').read()
    require(sha(binary) == receipt['binary_sha256'], 'Wrong candidate executable bytes')
    for name in ('candidate-rootfs', 'reference-rootfs'):
        (inputs / name).mkdir()
    (inputs / 'candidate-rootfs/trivy').write_bytes(binary)
    (inputs / 'candidate-rootfs/trivy').chmod(0o755)
    subprocess.run(['docker', 'pull', '--platform', 'linux/amd64', REFERENCE], check=True)
    cid = subprocess.check_output(['docker', 'create', REFERENCE], text=True).strip()
    try:
        subprocess.run(['docker', 'cp', cid + ':/usr/local/bin/trivy', str(inputs / 'reference-rootfs/trivy')], check=True)
    finally:
        subprocess.run(['docker', 'rm', cid], check=True)
    require(sha((inputs / 'reference-rootfs/trivy').read_bytes()) == REFERENCE_BINARY,
            'Wrong positive vulnerability fixture executable')
    actual = {}
    for index, image in enumerate(actual_images):
        target = inputs / f'actual-{index}.tar'
        subprocess.run(['docker', 'save', '-o', str(target), image], check=True)
        actual[target.name] = json.loads(subprocess.check_output(['docker', 'image', 'inspect', image]))[0]['Id']
    cache = directory / 'cache'
    cache.mkdir()
    temporary = directory / 'temporary'
    temporary.mkdir()
    # The authoritative Java index blob exceeds 900 MiB. Keep its temporary
    # download on private disk without exposing the checkout or Docker socket.
    scratch = ['-v', f'{temporary.resolve()}:/tmp'] if actual_images else ['--tmpfs', '/tmp:rw,size=256m,mode=1777']
    base = ['docker', 'run', '--rm', '--platform', 'linux/amd64', '--read-only', '--cap-drop', 'ALL',
            '--security-opt', 'no-new-privileges', '--pids-limit', '128', '--memory', '4g', '--cpus', '2',
            '--user', f'{os.getuid()}:{os.getgid()}', *scratch,
            '-v', f'{inputs.resolve()}:/input:ro', '-v', f'{cache.resolve()}:/cache']
    update = base + ['-v', f'{directory.resolve()}:/evidence']
    certificate = os.environ.get('AUXILIARY_SCAN_CA_BUNDLE')
    if certificate:
        update += ['-v', f'{Path(certificate).resolve()}:/run/ca.pem:ro', '-e', 'SSL_CERT_FILE=/run/ca.pem']
    args = ['image', '--download-db-only']
    if os.environ.get('TRIVY_DB_REPOSITORY'):
        args += ['--db-repository', os.environ['TRIVY_DB_REPOSITORY']]
    with (directory / 'database-update.log').open('w') as log:
        subprocess.run(update + [REFERENCE, '--cache-dir', '/cache'] + args, check=True, stdout=log, stderr=subprocess.STDOUT)
    if actual_images:
        with (directory / 'java-database-update.log').open('w') as log:
            subprocess.run(update + [REFERENCE, '--cache-dir', '/cache', 'image', '--download-java-db-only', '--java-db-repository',
                                    'ghcr.io/aquasecurity/trivy-java-db:1'], check=True, stdout=log, stderr=subprocess.STDOUT)
    metadata = database(cache / 'db/metadata.json')
    write(directory / 'database-metadata.json', metadata)
    initial_db = sha((cache / 'db/trivy.db').read_bytes())
    cases = {'vulnerable': ('sbom', '/input/vulnerable.cdx.json'),
             'patched': ('sbom', '/input/patched.cdx.json'),
             'reference-rootfs': ('rootfs', '/input/reference-rootfs'),
             'candidate-rootfs': ('rootfs', '/input/candidate-rootfs'),
             'candidate-image': ('image', '/input/candidate.tar')}
    cases.update({name: ('image', '/input/' + name) for name in actual})
    outputs = {}
    for scanner, image in [('reference', REFERENCE), ('candidate', ref)]:
        item = directory / scanner
        item.mkdir()
        command = base + ['--network', 'none', '-v', f'{item.resolve()}:/evidence', image, '--cache-dir', '/cache']
        summaries = {}
        for name, (kind, target) in cases.items():
            scan = [kind, '--skip-db-update', '--ignorefile', '/dev/null', '--scanners', 'vuln', '--list-all-pkgs',
                    '--exit-code', '0', '--format', 'json', '--output', '/evidence/' + name + '.raw.json']
            scan += ['--input', target] if kind == 'image' else [target]
            if kind == 'image' and actual_images:
                scan += ['--skip-java-db-update']
            with (item / (name + '.scan.log')).open('w') as log:
                subprocess.run(command + scan, check=True, stdout=log, stderr=subprocess.STDOUT)
            subprocess.run(command + ['convert', '--format', 'cyclonedx', '--output', '/evidence/' + name + '.cdx.json',
                                      '/evidence/' + name + '.raw.json'], check=True, stdout=subprocess.DEVNULL)
            raw, bom = read(item / (name + '.raw.json')), read(item / (name + '.cdx.json'))
            raw_purls = packages(raw)
            require(bom['bomFormat'] == 'CycloneDX' and bom['specVersion'] == '1.7'
                    and raw_purls == {p['purl'] for p in bom['components'] if 'purl' in p}, 'Incomplete converted package readback')
            if name in actual:
                require(raw['Metadata']['ImageID'] == actual[name], 'Wrong actual repository image')
            summaries[name] = {'purls': sorted(raw_purls), 'findings': sorted(findings(raw)),
                               'blocked': sorted(findings(raw, True))}
        require(('CVE-2025-48734', 'commons-beanutils:commons-beanutils', '1.9.4', 'HIGH') in
                findings(read(item / 'vulnerable.raw.json'), True), 'Missing known HIGH positive finding')
        require(not summaries['patched']['blocked'], 'Negative fixture has blocking findings')
        require(set(map(tuple, summaries['reference-rootfs']['blocked'])) == EXPECTED,
                'Known vulnerable scanner fixture did not retain all four blockers')
        require(not summaries['candidate-rootfs']['blocked'] and not summaries['candidate-image']['blocked'],
                'Actual candidate still has HIGH/CRITICAL findings')
        statuses = {}
        for name, options in [('malformed', ['sbom', '--skip-db-update', '/input/malformed.cdx.json']),
                              ('severity-positive', ['rootfs', '--skip-db-update', '--scanners', 'vuln', '--severity',
                                                     'HIGH,CRITICAL', '--exit-code', '1', '/input/reference-rootfs'])]:
            with (item / (name + '.log')).open('w') as log:
                status = subprocess.run(command + options, stdout=log, stderr=subprocess.STDOUT).returncode
            require(status == 1 if name == 'severity-positive' else status != 0,
                    'Incorrect scanner failure/severity behavior: ' + name)
            statuses[name] = status
        empty = item / 'empty-cache'
        empty.mkdir()
        missing = command.copy()
        missing[missing.index(f'{cache.resolve()}:/cache')] = f'{empty.resolve()}:/cache'
        with (item / 'missing-database.log').open('w') as log:
            status = subprocess.run(missing + ['sbom', '--skip-db-update', '/input/patched.cdx.json'],
                                    stdout=log, stderr=subprocess.STDOUT).returncode
        require(status != 0, 'Missing database incorrectly accepted')
        statuses['missing-database'] = status
        outputs[scanner] = {'cases': summaries, 'failure_statuses': statuses}
    require(outputs['reference'] == outputs['candidate'], 'Scanner behavior/package/advisory disagreement')
    require(metadata == database(cache / 'db/metadata.json') and initial_db == sha((cache / 'db/trivy.db').read_bytes()),
            'Scanner changed database bytes or metadata during offline compatibility checks')
    result = {'repository_head': receipt['repository_head'], 'candidate': ref, 'reference_fixture': REFERENCE,
        'reference_fixture_execution_authority': False, 'database': metadata, 'database_sha256': initial_db,
        'actual_images': actual, 'outputs': outputs, 'byte_inputs': {p.relative_to(inputs).as_posix(): sha(p.read_bytes())
            for p in inputs.rglob('*') if p.is_file()}, 'verified': True,
        'evidence_hashes': {p.relative_to(directory).as_posix(): sha(p.read_bytes()) for p in directory.rglob('*')
            if p.is_file() and not {'inputs', 'cache', 'temporary'}.intersection(p.relative_to(directory).parts)}}
    write(directory / 'result.json', result)
    shutil.rmtree(inputs)
    shutil.rmtree(cache)
    shutil.rmtree(temporary)
    print('Verified image/rootfs/SBOM/convert/package/severity/database compatibility with pinned positive and negative fixtures.')
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build', type=Path, default=EVIDENCE.runtime_directory())
    parser.add_argument('--directory', type=Path, default=ROOT / '.tmp/trivy-compatibility')
    parser.add_argument('--image', action='append', default=[])
    args = parser.parse_args()
    try:
        collect(args.build.resolve(), args.directory.resolve(), args.image)
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        parser.exit(1, 'Trivy compatibility failed: ' + str(error) + '\n')
