#!/usr/bin/env python3
"""Offline JSSE and guard regressions in the actual builder and runtime images."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import uuid


ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / 'scripts/tests/fixtures/java-blocklist'
JAVA = '/opt/java/openjdk/bin/'
BLOCKLIST = '/opt/java/openjdk/lib/security/blocked.certs'
GUARD = '/usr/local/bin/with-verified-blocklist'


def execute(command):
    name = None
    if command[:2] == ['docker', 'run']:
        name = 'shop-java-blocklist-' + uuid.uuid4().hex
        command = [*command[:2], '--name', name, *command[2:]]
    try:
        return subprocess.run(command, text=True, capture_output=True, timeout=120)
    except subprocess.TimeoutExpired:
        if name:
            subprocess.run(['docker', 'rm', '-f', name], capture_output=True, timeout=30, check=True)
        raise


def run(command):
    result = execute(command)
    if result.returncode:
        raise RuntimeError(f'Command failed ({result.returncode}): {command}\n{result.stdout}\n{result.stderr}')
    return result.stdout + result.stderr


def verify_fixtures():
    receipt = json.loads((FIXTURES / 'provenance.json').read_text())
    for name, entry in receipt['files'].items():
        if hashlib.sha256((FIXTURES / name).read_bytes()).hexdigest() != entry['sha256']:
            raise ValueError(f'Fixture checksum mismatch: {name}')
    inherited = set((FIXTURES / 'vendor-blocked.certs').read_text().splitlines()[1:])
    replacement = set((ROOT / 'docker/java-security/blocked.certs').read_text().splitlines()[1:])
    if len(inherited) != 38 or not inherited <= replacement:
        raise ValueError('Inherited restrictions were removed')


def verify(output):
    verify_fixtures()
    images = {}
    for stage in ('builder', 'app'):
        image = json.loads(run(['docker', 'image', 'inspect', f'enterprise-shop/{stage}:ci']))[0]
        if (image['Os'], image['Architecture']) != ('linux', 'amd64'):
            raise ValueError('Expected linux/amd64 images')
        if stage == 'app' and image['Config']['User'] != 'app:app':
            raise ValueError('Runtime must use the application user')
        images[stage] = image['Id']
    evidence = {'source_sha': run(['git', '-C', str(ROOT), 'rev-parse', 'HEAD']).strip(),
                'images': images, 'results': {}}
    with tempfile.TemporaryDirectory(prefix='java-blocklist-') as temporary:
        work = Path(temporary)
        work.chmod(0o755)
        common = ['docker', 'run', '--rm', '--platform', 'linux/amd64', '--network', 'none',
                  '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
                  '--pids-limit', '128', '--memory', '512m', '--cpus', '2',
                  '--tmpfs', '/tmp:rw,nosuid,nodev',
                  '--mount', f'type=bind,src={FIXTURES},dst=/fixtures,readonly']
        build = [*common, '--user', f'{os.getuid()}:{os.getgid()}',
                 '--mount', f'type=bind,src={work},dst=/work',
                 '--mount', f'type=bind,src={ROOT / "src/test/java"},dst=/source,readonly',
                 '--entrypoint', GUARD, images['builder'], BLOCKLIST]
        run([*build, JAVA + 'javac', '-d', '/work', '/source/BlocklistRegression.java'])
        # Generate disposable local CA/server keys; no private material is committed or uploaded.
        keytool = [*build, JAVA + 'keytool']
        for alias, store, name, extensions in (
                ('ca', 'ca.p12', 'CN=Regression Test CA', ['-ext', 'bc:c', '-ext', 'ku=keyCertSign']),
                ('server', 'server.p12', 'CN=localhost', ['-ext', 'san=dns:localhost'])):
            run([*keytool, '-genkeypair', '-alias', alias, '-keystore', '/work/' + store,
                 '-storetype', 'PKCS12', '-storepass', 'fixture-only', '-keyalg', 'RSA',
                 '-keysize', '2048', '-dname', name, '-validity', '2', *extensions])
        run([*keytool, '-exportcert', '-alias', 'ca', '-keystore', '/work/ca.p12',
             '-storepass', 'fixture-only', '-file', '/work/ca.der'])
        run([*keytool, '-certreq', '-alias', 'server', '-keystore', '/work/server.p12',
             '-storepass', 'fixture-only', '-file', '/work/server.csr'])
        run([*keytool, '-gencert', '-alias', 'ca', '-keystore', '/work/ca.p12',
             '-storepass', 'fixture-only', '-infile', '/work/server.csr', '-outfile', '/work/server.der',
             '-validity', '2', '-ext', 'san=dns:localhost', '-ext', 'eku=serverAuth',
             '-ext', 'ku=digitalSignature,keyEncipherment'])
        for alias, filename in (('ca', 'ca.der'), ('server', 'server.der')):
            run([*keytool, '-importcert', '-noprompt', '-alias', alias, '-keystore', '/work/server.p12',
                 '-storepass', 'fixture-only', '-file', '/work/' + filename])
        for path in work.iterdir():
            path.chmod(0o644)
        mounted = [*common, '--mount', f'type=bind,src={work},dst=/work,readonly']
        for stage, image in images.items():
            java = [JAVA + 'java', '-cp', '/work', 'BlocklistRegression', '/fixtures', '/work/server.p12']
            candidate = run([*mounted, '--entrypoint', GUARD, image, BLOCKLIST, *java, 'blocked'])
            # Deliberately unmitigated, isolated control proves rejection is due to the data,
            # not expired fixtures, missing roots or invalid signatures. Never run application code here.
            control = run([*mounted, '--mount',
                           f'type=bind,src={FIXTURES / "vendor-blocked.certs"},dst={BLOCKLIST},readonly',
                           '--entrypoint', JAVA + 'java', image, *java[1:], 'control'])
            if stage == 'app':
                # Check actual DAC permissions without a read-only rootfs masking writable paths.
                permissions = [argument for argument in mounted if argument != '--read-only']
                run([*permissions, '--entrypoint', '/bin/sh', image, '-ec',
                     'test ! -w "$1" && test ! -w "$2" && test ! -w "$3" && test ! -w "$4"',
                     'permissions', BLOCKLIST, str(Path(BLOCKLIST).parent), GUARD, str(Path(GUARD).parent)])
            invalid = work / 'invalid-blocklist'
            original = (ROOT / 'docker/java-security/blocked.certs').read_bytes()
            negatives = []
            for label, data, mode in [('empty', b'', 0o444), ('truncated', original[:-1], 0o444),
                                      ('modified', original + b'00\n', 0o444),
                                      ('algorithm', original.replace(b'SHA-256', b'SHA-512'), 0o444),
                                      ('unreadable', original, 0)]:
                if invalid.exists():
                    invalid.chmod(0o600)
                invalid.write_bytes(data)
                invalid.chmod(mode)
                result = execute([*mounted, '--mount', f'type=bind,src={invalid},dst={BLOCKLIST},readonly',
                                  '--entrypoint', GUARD, image, BLOCKLIST, JAVA + 'java', '-version'])
                if result.returncode == 0 or 'refusing to start' not in result.stderr or 'openjdk version' in result.stderr:
                    raise AssertionError(f'{stage}: guard failed for {label}: {result.stderr}')
                negatives.append(label)
            invalid.chmod(0o600)
            missing = execute([*mounted, '--entrypoint', GUARD, image,
                               '/missing-blocklist', JAVA + 'java', '-version'])
            if missing.returncode == 0 or 'refusing to start' not in missing.stderr:
                raise AssertionError('Missing blocklist did not fail closed')
            evidence['results'][stage] = {'candidate': candidate, 'unmitigated_control': control,
                                         'guard_negative_pass': [*negatives, 'missing']}
            print(f'{stage}: 4 blocked chains, 4 accepted controls, 6 TLS checks, 6 guard negatives passed')
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(evidence, indent=2) + '\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path,
                        default=ROOT / '.tmp/container-security/builder/java-blocklist-regression.json')
    arguments = parser.parse_args()
    arguments.output.unlink(missing_ok=True)
    verify(arguments.output)
