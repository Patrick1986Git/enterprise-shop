#!/usr/bin/env python3
"""Verify the integration JVM's observed Testcontainers resources disappear."""
import json
import re
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def verify():
    properties = {}
    for line in (ROOT / 'target/ryuk-compatibility/execution.properties').read_text().splitlines():
        if line and not line.startswith('#'):
            key, value = line.split('=', 1)
            properties[key] = value.replace('\\:', ':').replace('\\=', '=')
    expected = dict(line.split('=', 1) for line in
                    (ROOT / 'src/test/resources/testcontainers.properties').read_text().splitlines() if line)['ryuk.container.image']
    if properties['reference'] != expected:
        raise ValueError('Wrong actual Testcontainers Ryuk execution reference')
    candidate = json.loads((ROOT / '.github/security/ryuk/candidate.json').read_text())
    if (properties['image_id'] != candidate['image_id'] or expected != candidate['image_reference']
            or properties.get('socket_verified') != 'true'):
        raise ValueError('Wrong actual candidate image configuration or Docker socket evidence')
    ids = set(properties['cleanup_container_ids'].split(',')) | {properties['ryuk_container_id']}
    if not ids or not all(re.fullmatch('[0-9a-f]{64}', identifier) for identifier in ids):
        raise ValueError('Missing observed cleanup resources')
    deadline = time.monotonic() + 45
    remaining = set(ids)
    while remaining:
        for identifier in list(remaining):
            result = subprocess.run(['docker', 'container', 'inspect', identifier], capture_output=True, text=True)
            if result.returncode:
                if 'No such container' not in result.stderr and 'No such object' not in result.stderr:
                    raise ValueError('Docker inspection failed: ' + result.stderr)
                remaining.remove(identifier)
        if remaining:
            if time.monotonic() >= deadline:
                raise ValueError('Testcontainers/Ryuk resources remain after integration JVM exit: ' + ','.join(sorted(remaining)))
            time.sleep(1)
    directory = ROOT / '.tmp/ryuk-compatibility'
    directory.mkdir(parents=True, exist_ok=True)
    (directory / 'cleanup.json').write_text(json.dumps({'reference': expected, 'image_id': properties['image_id'],
        'socket_verified': True, 'removed_container_ids': sorted(ids), 'cleanup_verified': True}, indent=2) + '\n')
    print('Actual pinned Ryuk startup and post-JVM resource cleanup verified')


if __name__ == '__main__':
    verify()
