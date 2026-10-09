#!/usr/bin/env python3
"""Verify upstream archive attestation using runner-provided gh; compare scanner bytes."""
import hashlib
import io
import json
import subprocess
import tarfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
directory = ROOT / '.tmp/container-security/auxiliary/scanner-provenance'
directory.mkdir(parents=True, exist_ok=True)
(directory / 'result.json').unlink(missing_ok=True)
contract = json.loads((ROOT / '.github/security/auxiliary-container-scope.json').read_text())
version = contract['scanner_version']
reference = contract['images']['scanner']['reference']
release_url = 'https://api.github.com/repos/aquasecurity/trivy/releases/tags/v' + version
with urllib.request.urlopen(release_url, timeout=60) as response:
    release = json.load(response)
assert release['tag_name'] == 'v' + version and not release['draft'] and not release['prerelease']
asset_name = f'trivy_{version}_Linux-64bit.tar.gz'
assets = [a for a in release['assets'] if a['name'] == asset_name]
assert len(assets) == 1 and assets[0]['digest'].startswith('sha256:')
asset = assets[0]
assert asset['browser_download_url'] == f'https://github.com/aquasecurity/trivy/releases/download/v{version}/{asset_name}'
archive = directory / asset_name
with urllib.request.urlopen(asset['browser_download_url'], timeout=60) as response:
    data = response.read()
assert 'sha256:' + hashlib.sha256(data).hexdigest() == asset['digest']
archive.write_bytes(data)
# This verifies Aqua's SLSA provenance through existing hosted-runner tooling.
# No downloaded scanner, Cosign, second database, or privileged verifier is executed.
with (directory / 'attestation.json').open('w') as output:
    subprocess.run(['gh', 'attestation', 'verify', str(archive), '--repo', 'aquasecurity/trivy',
                    '--signer-workflow', 'aquasecurity/trivy/.github/workflows/reusable-release.yaml',
                    '--source-ref', 'refs/tags/v' + version, '--format', 'json'], check=True, stdout=output)
with tarfile.open(fileobj=io.BytesIO(data), mode='r:gz') as upstream:
    binaries = [m for m in upstream.getmembers() if m.name == 'trivy' and m.isfile()]
    assert len(binaries) == 1
    upstream_hash = hashlib.sha256(upstream.extractfile(binaries[0]).read()).hexdigest()
container = subprocess.check_output(['docker', 'create', '--platform', 'linux/amd64', reference], text=True).strip()
try:
    binary = directory / 'image-trivy'
    subprocess.run(['docker', 'cp', container + ':/usr/local/bin/trivy', str(binary)], check=True)
    observed = hashlib.sha256(binary.read_bytes()).hexdigest()
    assert observed == upstream_hash == contract['images']['scanner']['identity']['sha256']
finally:
    subprocess.run(['docker', 'rm', container], check=True)
(directory / 'result.json').write_text(json.dumps({'verified': True, 'version': version, 'image': reference,
    'method': contract['scanner_independent_integrity'], 'archive_sha256': asset['digest'],
    'executable_sha256': observed, 'release_id': release['id'], 'asset_id': asset['id'],
    'source_ref': 'refs/tags/v' + version,
    'signer_workflow': 'aquasecurity/trivy/.github/workflows/reusable-release.yaml'}, indent=2) + '\n')
archive.unlink()
binary.unlink()
