#!/usr/bin/env python3
"""Verify upstream archive attestation using runner-provided gh; compare scanner bytes."""
import argparse
import hashlib
import importlib.util
import io
import json
import os
import re
import subprocess
import tarfile
import urllib.request
from pathlib import Path
from urllib.error import HTTPError

from github_api import GitHubAPIError, fetch_json

ROOT = Path(__file__).resolve().parent.parent
SOURCE_IMAGE = 'ghcr.io/aquasecurity/trivy:0.75.0@sha256:af6acf9a6b85dfe389a1941505c0ce9efef52a4719635e1a962f022a3d855daa'
SOURCE_BINARY = '93f9da8e4ba5e0c1c76d8234ed2494cf9afb0a96fd21953e424bb795f3299b8e'
SOURCE_COMMIT = '591e9799316a602e703f0b484f6c6d7b234ec8f3'
SIGNER_WORKFLOW = 'aquasecurity/trivy/.github/workflows/reusable-release.yaml'


def require(condition, message):
    if not condition:
        raise ValueError(message)


def verify_upstream(directory, version, upstream_reference):
    release_url = 'https://api.github.com/repos/aquasecurity/trivy/releases/tags/v' + version
    release = fetch_json(release_url, user_agent='enterprise-shop-trivy-provenance',
                         token=os.environ.get('GH_TOKEN'))
    require(isinstance(release, dict) and release.get('tag_name') == 'v' + version
            and release.get('draft') is False and release.get('prerelease') is False
            and type(release.get('id')) is int and release['id'] > 0, 'Invalid Trivy release identity')
    asset_name = f'trivy_{version}_Linux-64bit.tar.gz'
    release_assets = release.get('assets')
    require(isinstance(release_assets, list) and all(
        isinstance(asset, dict) and isinstance(asset.get('name'), str) for asset in release_assets
    ), 'Malformed Trivy release assets')
    assets = [asset for asset in release_assets if asset['name'] == asset_name]
    require(len(assets) == 1, 'Expected a single Linux amd64 Trivy release asset')
    asset = assets[0]
    digest = asset.get('digest')
    require(type(asset.get('id')) is int and asset['id'] > 0 and isinstance(digest, str)
            and re.fullmatch(r'sha256:[0-9a-f]{64}', digest), 'Invalid Trivy release asset identity or digest')
    download_url = f'https://github.com/aquasecurity/trivy/releases/download/v{version}/{asset_name}'
    require(asset.get('browser_download_url') == download_url, 'Unexpected Trivy release download URL')
    archive = directory / asset_name
    # The public download can redirect to GitHub's asset CDN. It has no API credential.
    try:
        with urllib.request.urlopen(download_url, timeout=60) as response:
            data = response.read()
    except HTTPError as error:
        raise ValueError(f'Upstream Trivy release download returned HTTP {error.code}') from None
    except OSError:
        raise ValueError('Upstream Trivy release download could not be completed') from None
    require('sha256:' + hashlib.sha256(data).hexdigest() == digest, 'Trivy release archive digest mismatch')
    archive.write_bytes(data)

    # This verifies Aqua's SLSA provenance through existing hosted-runner tooling.
    attestation = directory / 'attestation.json'
    try:
        with attestation.open('w') as output:
            subprocess.run(['gh', 'attestation', 'verify', str(archive), '--repo', 'aquasecurity/trivy',
                            '--signer-workflow', SIGNER_WORKFLOW, '--source-ref', 'refs/tags/v' + version,
                            '--format', 'json'], check=True, stdout=output, stderr=subprocess.PIPE)
    except (OSError, subprocess.CalledProcessError):
        raise ValueError('Upstream Trivy archive attestation verification failed') from None
    with tarfile.open(fileobj=io.BytesIO(data), mode='r:gz') as upstream:
        binaries = [member for member in upstream.getmembers() if member.name == 'trivy' and member.isfile()]
        require(len(binaries) == 1, 'Expected a single Trivy executable in the verified archive')
        upstream_hash = hashlib.sha256(upstream.extractfile(binaries[0]).read()).hexdigest()
    container = subprocess.check_output(['docker', 'create', '--platform', 'linux/amd64', upstream_reference],
                                        text=True).strip()
    try:
        binary = directory / 'image-trivy'
        subprocess.run(['docker', 'cp', container + ':/usr/local/bin/trivy', str(binary)], check=True)
        observed = hashlib.sha256(binary.read_bytes()).hexdigest()
        require(observed == upstream_hash == SOURCE_BINARY, 'Upstream Trivy executable identity mismatch')
    finally:
        subprocess.run(['docker', 'rm', container], check=True)
    try:
        attestations = json.loads(attestation.read_text())
        matches_source = any(
            entry['verificationResult']['signature']['certificate']['sourceRepositoryDigest'] == SOURCE_COMMIT
            for entry in attestations
        )
    except (KeyError, TypeError, ValueError):
        raise ValueError('Malformed upstream Trivy attestation evidence') from None
    require(matches_source, 'Upstream Trivy attestation is missing the reviewed source commit')
    upstream = {'verified': True, 'version': version, 'image': upstream_reference,
        'method': 'upstream-github-attestation-and-executable-byte-match', 'archive_sha256': digest,
        'executable_sha256': observed, 'release_id': release['id'], 'asset_id': asset['id'],
        'source_ref': 'refs/tags/v' + version, 'source_commit': SOURCE_COMMIT, 'signer_workflow': SIGNER_WORKFLOW}
    (directory / 'upstream.json').write_text(json.dumps(upstream, indent=2) + '\n')
    archive.unlink()
    binary.unlink()
    return upstream


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--upstream-only', action='store_true')
    parser.add_argument('--build', type=Path)
    args = parser.parse_args()
    directory = ROOT / '.tmp/container-security/auxiliary/scanner-provenance'
    directory.mkdir(parents=True, exist_ok=True)
    (directory / 'result.json').unlink(missing_ok=True)
    (directory / 'upstream.json').unlink(missing_ok=True)
    contract = json.loads((ROOT / '.github/security/auxiliary-container-scope.json').read_text())
    version = contract['scanner_version']
    reference = contract['images']['scanner']['reference']
    candidate_build = contract['images']['scanner'].get('candidate_build', False)
    upstream_reference = SOURCE_IMAGE if candidate_build else reference
    try:
        upstream = verify_upstream(directory, version, upstream_reference)
    except GitHubAPIError as error:
        parser.exit(1, f'Upstream Trivy release metadata could not be obtained: {error}\n')
    except (ValueError, tarfile.TarError) as error:
        parser.exit(1, f'Upstream Trivy provenance verification failed: {error}\n')
    if args.upstream_only:
        return
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
        require(upstream['executable_sha256'] == contract['images']['scanner']['identity']['sha256'],
                'Unexpected Trivy executable identity')
        result = upstream
    (directory / 'result.json').write_text(json.dumps(result, indent=2) + '\n')


if __name__ == '__main__':
    main()
