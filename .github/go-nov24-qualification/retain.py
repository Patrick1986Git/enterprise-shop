"""Retain raw evidence from this run without publishing caches or checkout state."""
import hashlib
import json
import os
from pathlib import Path
import re
import sys


TOP = re.compile(r'(?:binding|runner-capabilities|metadata|connectivity|accounting-(?:late|oom)-fixture|'
                 r'preflight-status|collection-status|swap-(?:provisioning|cleanup)|source-db-(?:before|after))\.json|db\.json\.gz')
PHASES = ('buildkit', 'trivy', 'trivy/first', 'trivy/independent-source', 'ryuk', 'ryuk/first', 'ryuk/repeat')
RAW = re.compile(r'[a-z0-9._-]+\.(?:json|jsonl|log|txt|crt|tar|tar\.gz)|'
                 r'govulncheck|trivy\.(?:first|repeat)|ryuk(?:\.repeat)?|ryuk-sdk-[a-z0-9_]+')


def retain(evidence, candidate, target):
    target.mkdir()
    files = {}
    total = 0

    def save(path, relative):
        nonlocal total
        if not path.is_file():
            return
        if path.resolve() != path.absolute():
            raise ValueError('Unexpected link in retained evidence')
        size = path.stat().st_size
        total += size
        if total > 2 * 1024 ** 3:
            raise ValueError('Qualification payload exceeds its retention bound')
        dest = target / relative
        dest.parent.mkdir(parents=True, exist_ok=True)
        os.link(path, dest, follow_symlinks=False)
        with dest.open('rb') as stream:
            files[relative] = {'sha256': hashlib.file_digest(stream, 'sha256').hexdigest(), 'bytes': size}

    def collect(directory, prefix, allowed):
        if directory.is_dir():
            for path in sorted(directory.iterdir()):
                if allowed.fullmatch(path.name):
                    save(path, prefix + path.name)

    collect(evidence, '', TOP)
    for phase in PHASES:
        collect(evidence / phase, phase + '/', RAW)
    upstream = candidate / '.tmp/container-security/auxiliary/scanner-provenance'
    for name in ('upstream.json', 'attestation.json'):
        save(upstream / name, 'trivy-upstream/' + name)
    event = {key: os.environ[key] for key in ('GITHUB_REPOSITORY', 'GITHUB_REF', 'GITHUB_SHA', 'GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT')}
    (target / 'retained-files.json').write_text(json.dumps({'event': event, 'files': files}, indent=2) + '\n')
    if 'binding.json' not in files:
        raise ValueError('Candidate or harness binding unavailable')


if __name__ == '__main__':
    retain(*(Path(arg).resolve() for arg in sys.argv[1:]))
