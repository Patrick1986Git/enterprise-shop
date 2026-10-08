#!/usr/bin/env python3
"""Collect image archives, installed packages, raw Trivy reports and converted SBOMs."""
import hashlib
import importlib.util
import json
import os
import subprocess
import tarfile
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('auxiliary_policy', ROOT / 'scripts/validate-auxiliary-containers.py')
POLICY = importlib.util.module_from_spec(spec)
spec.loader.exec_module(POLICY)


def docker(*args):
    return subprocess.check_output(['docker', *args], text=True)


def archive_contents(path, binary):
    contents = {}
    # Read selected files as data. Never extract tar members onto the runner or execute them.
    with tarfile.open(path) as archive:
        manifest = json.load(archive.extractfile('manifest.json'))
        POLICY.require(len(manifest) == 1, 'Ambiguous saved image')
        for layer in manifest[0]['Layers']:
            with tarfile.open(fileobj=archive.extractfile(layer)) as filesystem:
                for member in filesystem:
                    name = member.name.removeprefix('./')
                    if member.isfile() and name in {'lib/apk/db/installed', binary.lstrip('/')}:
                        contents[name] = filesystem.extractfile(member).read()
    packages = {}
    if 'lib/apk/db/installed' in contents:
        for paragraph in contents['lib/apk/db/installed'].decode().split('\n\n'):
            fields = dict(line.split(':', 1) for line in paragraph.splitlines() if ':' in line)
            if 'P' in fields:
                POLICY.require(fields['P'] not in packages and fields['V'], 'Malformed installed apk inventory')
                packages[fields['P']] = fields['V']
    POLICY.require(binary.lstrip('/') in contents, 'Expected executable is absent from image')
    return packages, hashlib.sha256(contents[binary.lstrip('/')]).hexdigest()


def scan():
    contract = POLICY.read(POLICY.CONTRACT)
    images = POLICY.inventory(contract)
    directory = ROOT / '.tmp/container-security/auxiliary'
    directory.mkdir(parents=True, exist_ok=True)
    (directory / 'policy-result.json').unlink(missing_ok=True)
    source = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    POLICY.require(source == os.environ.get('EXPECTED_SOURCE_SHA'), 'Wrong exact checkout source')
    (directory / 'source-sha.txt').write_text(source + '\n')
    cache = ROOT / '.tmp/container-security/trivy-cache'
    database_status = int((ROOT / '.tmp/container-security/builder/database-exit-status.txt').read_text())
    database = POLICY.read(cache / 'db/metadata.json')
    for name, image in images.items():
        if not image['governed']:
            continue
        item = directory / name
        item.mkdir(exist_ok=True)
        for target in ('raw.json', 'sbom.cdx.json', 'identity.json'):
            (item / target).unlink(missing_ok=True)
        try:
            ref = image['reference']
            inspect = json.loads(docker('image', 'inspect', ref))[0]
            POLICY.require(inspect['Os'] + '/' + inspect['Architecture'] == 'linux/amd64', 'Wrong resolved platform')
            POLICY.require(any(d.endswith('@' + ref.split('@')[1]) for d in inspect['RepoDigests']),
                           'Pulled image does not resolve reviewed source digest')
            index = json.loads(docker('buildx', 'imagetools', 'inspect', '--raw', ref))
            resolved = ref.split('@')[1]
            if 'manifests' in index:
                matches = [x for x in index['manifests'] if x.get('platform', {}).get('os') == 'linux'
                           and x['platform'].get('architecture') == 'amd64']
                POLICY.require(len(matches) == 1, 'Missing/ambiguous linux/amd64 manifest')
                resolved = matches[0]['digest']
                manifest = json.loads(docker('buildx', 'imagetools', 'inspect', '--raw', ref.split('@')[0] + '@' + resolved))
            else:
                manifest = index
            POLICY.require(manifest['config']['digest'] == inspect['Id'], 'Resolved manifest/image configuration mismatch')
            POLICY.write(item / 'registry-manifest.json', manifest)
            POLICY.write(item / 'registry-index.json', index)
            POLICY.write(item / 'docker-image.json', inspect)
            archive = item / 'image.tar'
            subprocess.run(['docker', 'save', '-o', str(archive), ref], check=True)
            packages, binary_hash = archive_contents(archive, image['identity']['binary'])
            POLICY.require(binary_hash == image['identity']['sha256'], 'Reviewed executable byte identity changed')
            if image['identity'].get('command'):
                output = docker('run', '--rm', '--platform', 'linux/amd64', '--network', 'none',
                                ref, *image['identity']['command']).splitlines()[0]
                POLICY.require(output == image['identity']['output'], 'Expected executable version changed')
            command = ['docker', 'run', '--rm', '--platform', 'linux/amd64',
                       '-v', f'{archive}:/input/{name}.tar:ro',
                       '-v', f'{cache}:/root/.cache/trivy', '-v', f'{item}:/evidence']
            certificate = os.environ.get('AUXILIARY_SCAN_CA_BUNDLE')
            if certificate:
                command += ['-v', f'{Path(certificate).resolve()}:/run/scan-ca.pem:ro',
                            '-e', 'SSL_CERT_FILE=/run/scan-ca.pem']
            command += [images['scanner']['reference']]
            artifact = f'/input/{name}.tar'
            with (item / 'scanner.log').open('w') as output:
                scanner_status = subprocess.run(command + ['image', '--skip-db-update', '--ignorefile', '/dev/null',
                                                          '--scanners', 'vuln', '--list-all-pkgs', '--exit-code', '0',
                                                          '--format', 'json', '--output', '/evidence/raw.json',
                                                          '--input', artifact], stdout=output,
                                                stderr=subprocess.STDOUT).returncode
            # Convert the same raw package evidence; do not rescan the image for its SBOM.
            with (item / 'sbom.log').open('w') as output:
                sbom_status = subprocess.run(command + ['convert', '--format', 'cyclonedx', '--output',
                                                       '/evidence/sbom.cdx.json', '/evidence/raw.json'],
                                             stdout=output, stderr=subprocess.STDOUT).returncode
            POLICY.write(item / 'identity.json', {'reference': ref, 'platform': 'linux/amd64',
                         'resolved_digest': resolved, 'image_id': inspect['Id'], 'identity': image['identity'],
                         'installed_os_packages': packages, 'scanner_status': scanner_status,
                         'sbom_status': sbom_status, 'database_status': database_status, 'database': database,
                         'artifact_name': artifact, 'timestamp': datetime.now(timezone.utc).isoformat(),
                         'independent_scanner_integrity': False})
            archive.unlink()
        except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as error:
            POLICY.write(item / 'collection-error.json', {'error': str(error)})
            print(f'{name}: evidence collection failed: {error}', flush=True)
    # Failures remain visible and are blocked after evidence upload, not converted to clean results.


if __name__ == '__main__':
    scan()
