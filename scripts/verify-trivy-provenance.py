#!/usr/bin/env python3
"""Verify upstream archive attestation using runner-provided gh; compare scanner bytes."""
import argparse
import hashlib
import importlib.util
import io
import json
import subprocess
import tarfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE_IMAGE = 'ghcr.io/aquasecurity/trivy:0.75.0@sha256:af6acf9a6b85dfe389a1941505c0ce9efef52a4719635e1a962f022a3d855daa'
SOURCE_BINARY = '93f9da8e4ba5e0c1c76d8234ed2494cf9afb0a96fd21953e424bb795f3299b8e'
SOURCE_COMMIT = '591e9799316a602e703f0b484f6c6d7b234ec8f3'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--upstream-only', action='store_true')
parser.add_argument('--build', type=Path)
args = parser.parse_args()
directory = ROOT / '.tmp/container-security/auxiliary/scanner-provenance'
directory.mkdir(parents=True, exist_ok=True)
(directory / 'result.json').unlink(missing_ok=True)
contract = json.loads((ROOT / '.github/security/auxiliary-container-scope.json').read_text())
version = contract['scanner_version']
reference = contract['images']['scanner']['reference']
candidate_build = contract['images']['scanner'].get('candidate_build', False)
upstream_reference = SOURCE_IMAGE if candidate_build else reference
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
container = subprocess.check_output(['docker', 'create', '--platform', 'linux/amd64', upstream_reference], text=True).strip()
try:
    binary = directory / 'image-trivy'
    subprocess.run(['docker', 'cp', container + ':/usr/local/bin/trivy', str(binary)], check=True)
    observed = hashlib.sha256(binary.read_bytes()).hexdigest()
    assert observed == upstream_hash == SOURCE_BINARY
finally:
    subprocess.run(['docker', 'rm', container], check=True)
attestations = json.loads((directory / 'attestation.json').read_text())
assert any(a['verificationResult']['signature']['certificate']['sourceRepositoryDigest'] == SOURCE_COMMIT
           for a in attestations)
upstream = {'verified': True, 'version': version, 'image': upstream_reference,
    'method': 'upstream-github-attestation-and-executable-byte-match', 'archive_sha256': asset['digest'],
    'executable_sha256': observed, 'release_id': release['id'], 'asset_id': asset['id'],
    'source_ref': 'refs/tags/v' + version, 'source_commit': SOURCE_COMMIT,
    'signer_workflow': 'aquasecurity/trivy/.github/workflows/reusable-release.yaml'}
(directory / 'upstream.json').write_text(json.dumps(upstream, indent=2) + '\n')
archive.unlink()
binary.unlink()
if not args.upstream_only:
    if candidate_build:
        spec = importlib.util.spec_from_file_location('trivy_evidence', ROOT / 'scripts/trivy_candidate_evidence.py')
        evidence = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(evidence)
        reviewed = json.loads(evidence.CONTRACT.read_text())
        evidence.inventory(contract['images']['scanner'], reviewed)
        head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
        build = args.build.resolve() if args.build else evidence.runtime_directory()
        metadata, receipt, analysis = evidence.validate_build(reviewed, build, head)
        evidence.validate_upstream(directory)
        compatibility = build / 'compatibility'
        if not compatibility.exists():
            compatibility = ROOT / '.tmp/trivy-compatibility'
        evidence.validate_compatibility(compatibility, reviewed, head)
        result = {'verified': True, 'version': version, 'image': reference, 'method': evidence.METHOD,
            'repository_head': head, 'executable_sha256': reviewed['binary_sha256'],
            'image_id': reviewed['image_id'], 'upstream': upstream,
            'upstream_attestation_applies_to_rebuilt_binary': False,
            'independent_advisory_evaluation': analysis, 'behavior_verified': True}
    else:
        assert observed == contract['images']['scanner']['identity']['sha256']
        result = upstream
    (directory / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
