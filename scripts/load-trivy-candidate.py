#!/usr/bin/env python3
"""Verify same-workflow scanner evidence before loading its immutable local image."""
import argparse
import importlib.util
import io
import json
import shutil
import subprocess
import tarfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SPEC = importlib.util.spec_from_file_location('trivy_evidence', ROOT / 'scripts/trivy_candidate_evidence.py')
EVIDENCE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(EVIDENCE)


def load(downloads, directory):
    require, sha = EVIDENCE.require, EVIDENCE.sha
    require(not directory.is_relative_to(ROOT.resolve()), 'Loaded scanner source must stay outside the checkout')
    require(not directory.exists(), 'Scanner evidence directory already exists')
    core = downloads / 'trivy-candidate-collection'
    shutil.copytree(core / 'trivy-candidate', directory)
    transport = json.loads((directory / 'transport.json').read_text())
    require(transport['chunks'] and len(transport['chunks']) <= 12
            and list(transport['chunks']) == [f'{i:02d}' for i in range(len(transport['chunks']))],
            'Missing/out-of-order scanner payload chunks')
    payload = directory / 'payload.tar.gz'
    with payload.open('wb') as output:
        for name, digest in transport['chunks'].items():
            data = (downloads / ('trivy-candidate-payload-' + name) / name).read_bytes()
            require(sha(data) == digest, 'Scanner transport chunk drift')
            output.write(data)
    require(sha(payload.read_bytes()) == transport['payload_sha256'], 'Scanner transport payload drift')
    expected = {'image.tar', 'trivy.repeat', 'govulncheck', 'upstream.tar.gz', 'sdk.tar.gz'}
    with tarfile.open(payload) as saved:
        require({m.name for m in saved.getmembers()} == expected and len(saved.getmembers()) == len(expected),
                'Unexpected scanner payload members')
        for member in saved:
            require(member.isfile(), 'Scanner payload cannot contain links/devices')
            (directory / member.name).write_bytes(saved.extractfile(member).read())
    payload.unlink()
    with tarfile.open(directory / 'image.tar') as saved:
        manifest = json.load(saved.extractfile('manifest.json'))
        require(len(manifest) == 1 and len(manifest[0]['Layers']) == 1, 'Ambiguous scanner image archive')
        layer = saved.extractfile(manifest[0]['Layers'][0]).read()
        (directory / 'layer.tar').write_bytes(layer)
        with tarfile.open(fileobj=io.BytesIO(layer)) as filesystem:
            (directory / 'trivy.first').write_bytes(filesystem.extractfile('usr/local/bin/trivy').read())
    EVIDENCE.BUILD.prepare((directory / 'upstream.tar.gz').read_bytes(), directory / 'source')
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    candidate = json.loads(EVIDENCE.CONTRACT.read_text())
    metadata, receipt, analysis = EVIDENCE.validate_build(candidate, directory, head)
    upstream = ROOT / '.tmp/container-security/auxiliary/scanner-provenance'
    require(not upstream.exists(), 'Upstream provenance directory already exists')
    shutil.copytree(core / 'container-security/auxiliary/scanner-provenance', upstream)
    EVIDENCE.validate_upstream(upstream)
    EVIDENCE.validate_compatibility(core / 'trivy-compatibility', candidate, head)
    shutil.copytree(core / 'trivy-compatibility', directory / 'compatibility')
    subprocess.run(['docker', 'load', '-i', str(directory / 'image.tar')], check=True)
    observed = json.loads(subprocess.check_output(['docker', 'image', 'inspect', candidate['image_reference']]))[0]
    require(observed['Id'] == candidate['image_id'], 'Loaded scanner image identity drift')
    print('Verified current-HEAD upstream source, independent compiler, repeat binaries, advisory analysis, package and behavior evidence.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--downloads', type=Path, default=ROOT / '.tmp/trivy-downloads')
    parser.add_argument('--directory', type=Path, default=EVIDENCE.runtime_directory())
    args = parser.parse_args()
    try:
        load(args.downloads.resolve(), args.directory.resolve())
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as error:
        parser.exit(1, 'Scanner loading rejected: ' + str(error) + '\n')
