#!/usr/bin/env python3
"""Collect image archives, installed packages, raw Trivy reports and converted SBOMs."""
import hashlib
import importlib.util
import json
import os
import shutil
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
    collection_images = dict(images)
    if contract.get('comparisons'):
        collection_images['official-ryuk-comparison'] = contract['comparisons']['official_ryuk']
    for name, image in collection_images.items():
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
            if image.get('candidate_build'):
                scanner_candidate = name == 'scanner'
                authority = POLICY.SCANNER if scanner_candidate else POLICY.CANDIDATE
                build = ROOT / ('.tmp/trivy-candidate' if scanner_candidate else '.tmp/ryuk-candidate')
                reviewed = POLICY.read(authority.CONTRACT)
                authority.validate_build(reviewed, build, source)
                POLICY.require(inspect['Id'] == reviewed['image_id'], 'Wrong loaded candidate')
                shutil.copytree(build, item, dirs_exist_ok=True)
                resolved = reviewed['image_id']
            else:
                POLICY.require(any(d.endswith('@' + ref.split('@')[1]) for d in inspect['RepoDigests']),
                               'Pulled image does not resolve reviewed source digest')
                index_bytes = docker('buildx', 'imagetools', 'inspect', '--raw', ref).encode()
                POLICY.require('sha256:' + hashlib.sha256(index_bytes).hexdigest() == ref.split('@')[1],
                               'Registry index digest mismatch')
                (item / 'registry-index.raw.json').write_bytes(index_bytes)
                index = json.loads(index_bytes)
                resolved = ref.split('@')[1]
                if 'manifests' in index:
                    matches = [x for x in index['manifests'] if x.get('platform', {}).get('os') == 'linux'
                               and x['platform'].get('architecture') == 'amd64']
                    POLICY.require(len(matches) == 1, 'Missing/ambiguous linux/amd64 manifest')
                    resolved = matches[0]['digest']
                    manifest_bytes = docker('buildx', 'imagetools', 'inspect', '--raw', ref.split('@')[0] + '@' + resolved).encode()
                    POLICY.require('sha256:' + hashlib.sha256(manifest_bytes).hexdigest() == resolved,
                                   'Registry platform manifest digest mismatch')
                    manifest = json.loads(manifest_bytes)
                else:
                    manifest = index
                    manifest_bytes = index_bytes
                (item / 'registry-manifest.raw.json').write_bytes(manifest_bytes)
                POLICY.require(manifest['config']['digest'] == inspect['Id'], 'Resolved manifest/image configuration mismatch')
                POLICY.write(item / 'registry-manifest.json', manifest)
                POLICY.write(item / 'registry-index.json', index)
            POLICY.write(item / 'docker-image.json', inspect)
            archive = item / 'image.tar'
            if not image.get('candidate_build'):
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
            if image.get('evidence_transform'):
                POLICY.require(scanner_status == 0 and sbom_status == 0, 'Original image scan failure')
                shutil.copyfile(item / 'raw.json', item / 'executed-image.raw.json')
                shutil.copyfile(item / 'sbom.cdx.json', item / 'executed-image.sbom.cdx.json')
                analysis_image = images['govulncheck']['reference']
                analysis = POLICY.BINARY.collect(image, contract['tools']['upx'], archive, item, analysis_image)
                command = command[:-1] + ['-v', f'{analysis.resolve()}:/analysis:ro'] + command[-1:]
                artifact = '/analysis'
                with (item / 'scanner.log').open('w') as output:
                    scanner_status = subprocess.run(command + ['rootfs', '--skip-db-update', '--ignorefile', '/dev/null',
                                                              '--scanners', 'vuln', '--list-all-pkgs', '--exit-code', '0',
                                                              '--format', 'json', '--output', '/evidence/raw.json', artifact],
                                                    stdout=output, stderr=subprocess.STDOUT).returncode
                with (item / 'sbom.log').open('w') as output:
                    sbom_status = subprocess.run(command + ['convert', '--format', 'cyclonedx', '--output',
                                                           '/evidence/sbom.cdx.json', '/evidence/raw.json'],
                                                 stdout=output, stderr=subprocess.STDOUT).returncode
                govuln = ['docker', 'run', '--rm', '--platform', 'linux/amd64', '--cap-drop', 'ALL',
                          '--security-opt', 'no-new-privileges', '-e', 'GOTOOLCHAIN=local',
                          '-e', 'GOVERSION=go' + image['go_version'], '-e', 'GOPATH=/tmp/go',
                          '-e', 'GOCACHE=/tmp/gocache', '-v', 'ryuk-govulncheck:/tmp',
                          '-v', f'{analysis.resolve()}:/analysis:ro']
                if certificate:
                    govuln += ['-v', f'{Path(certificate).resolve()}:/run/scan-ca.pem:ro', '-e', 'SSL_CERT_FILE=/run/scan-ca.pem']
                govuln += [analysis_image, 'sh', '-ec',
                           'go install golang.org/x/vuln/cmd/govulncheck@v1.1.4; '
                           'exec /tmp/go/bin/govulncheck -mode=binary -json "$1"', '--',
                           '/analysis' + image['identity']['binary']]
                with (item / 'govulncheck.json').open('w') as out, (item / 'govulncheck.log').open('w') as err:
                    govuln_status = subprocess.run(govuln, stdout=out, stderr=err, timeout=300).returncode
                receipt = POLICY.read(item / 'transformation.json')
                receipt.update({'raw_sha256': POLICY.BINARY.sha((item / 'raw.json').read_bytes()),
                                'sbom_sha256': POLICY.BINARY.sha((item / 'sbom.cdx.json').read_bytes()),
                                'govulncheck_status': govuln_status,
                                'govulncheck_sha256': POLICY.BINARY.sha((item / 'govulncheck.json').read_bytes())})
                POLICY.write(item / 'transformation.json', receipt)
            POLICY.write(item / 'identity.json', {'reference': ref, 'platform': 'linux/amd64',
                         'resolved_digest': resolved, 'image_id': inspect['Id'], 'identity': image['identity'],
                         'installed_os_packages': packages, 'scanner_status': scanner_status,
                         'sbom_status': sbom_status, 'database_status': database_status, 'database': database,
                         'artifact_name': artifact, 'timestamp': datetime.now(timezone.utc).isoformat(),
                         'identity_authority': 'local-configuration-and-archive' if image.get('candidate_build') else 'registry-oci',
                         'independent_scanner_integrity': False})
            if not image.get('candidate_build'):
                archive.unlink()
        except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as error:
            POLICY.write(item / 'collection-error.json', {'error': str(error)})
            print(f'{name}: evidence collection failed: {error}', flush=True)
    # Failures remain visible and are blocked after evidence upload, not converted to clean results.


if __name__ == '__main__':
    scan()
