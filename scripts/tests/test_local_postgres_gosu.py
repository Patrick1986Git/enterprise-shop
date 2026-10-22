import hashlib
import importlib.util
import io
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import textwrap
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'scripts/check-local-postgres-gosu.py'
SPEC = importlib.util.spec_from_file_location('local_postgres_gosu', SCRIPT)
PREFLIGHT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PREFLIGHT)


def archive(entries):
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode='w') as handle:
        for name, payload, mode, kind, link in entries:
            member = tarfile.TarInfo(name)
            member.mode = mode
            member.type = kind
            member.linkname = link
            member.size = len(payload) if kind == tarfile.REGTYPE else 0
            handle.addfile(member, io.BytesIO(payload) if member.isfile() else None)
    return output.getvalue()


def regular(name, payload=b'', mode=0o755):
    return name, payload, mode, tarfile.REGTYPE, ''


def link(name, target, kind=tarfile.SYMTYPE):
    return name, b'', 0o755, kind, target


class LocalPostgresGosuTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.payload = b'\x7fELF' + b'reviewed executable fixture'
        self.candidate = json.loads((ROOT / '.github/security/gosu/candidate.json').read_text())
        self.candidate['binary']['sha256'] = hashlib.sha256(self.payload).hexdigest()
        build = self.root / 'docker/postgres'
        (build / 'gosu').mkdir(parents=True)
        for name in self.candidate['lockfiles']:
            shutil.copyfile(ROOT / 'docker/postgres/gosu' / name, build / 'gosu' / name)
        original_hash = json.loads((ROOT / '.github/security/gosu/candidate.json').read_text())['binary']['sha256']
        (build / 'Dockerfile').write_text((ROOT / 'docker/postgres/Dockerfile').read_text().replace(
            original_hash, self.candidate['binary']['sha256']))

    def image_archive(self, layers, config_update=None):
        layer_payloads = [archive(entries) for entries in layers]
        config = {'architecture': 'amd64', 'os': 'linux',
                  'rootfs': {'type': 'layers', 'diff_ids': [
                      'sha256:' + hashlib.sha256(layer).hexdigest() for layer in layer_payloads]}}
        if config_update:
            config_update(config)
        config_payload = json.dumps(config).encode()
        digest = hashlib.sha256(config_payload).hexdigest()
        names = [f'{index}/layer.tar' for index in range(len(layers))]
        manifest = [{'Config': digest + '.json', 'RepoTags': ['fixture:latest'], 'Layers': names}]
        entries = [regular('manifest.json', json.dumps(manifest).encode()),
                   regular(digest + '.json', config_payload)]
        entries.extend(regular(name, payload) for name, payload in zip(names, layer_payloads))
        path = self.root / 'image.tar'
        path.write_bytes(archive(entries))
        return path, 'sha256:' + digest

    def test_reviewed_receipt_remains_the_only_executable_identity_authority(self):
        candidate = PREFLIGHT.load_candidate()
        self.assertEqual(candidate['binary'], json.loads(
            (ROOT / '.github/security/gosu/candidate.json').read_text())['binary'])

    def test_exact_reviewed_payload_and_executable_mode_pass(self):
        PREFLIGHT.validate_payload(self.payload, 0o755, self.candidate)

    def test_stale_unexpected_or_nonexecutable_payload_fails(self):
        for payload, mode in ((b'inherited vulnerable gosu', 0o755),
                              (self.payload + b'changed', 0o755), (self.payload, 0o644)):
            with self.subTest(payload=payload, mode=mode):
                with self.assertRaises(ValueError):
                    PREFLIGHT.validate_payload(payload, mode, self.candidate)

    def test_missing_unreadable_or_invalid_receipt_fails(self):
        for error in (FileNotFoundError('receipt'), PermissionError('receipt')):
            with self.subTest(error=type(error).__name__):
                with mock.patch.object(PREFLIGHT.PROVENANCE, 'regular_bytes', side_effect=error):
                    with self.assertRaises(OSError):
                        PREFLIGHT.load_candidate(self.root)
        for evidence in (b'{}', b'[]', b'null', b'"invalid receipt"', b'{malformed'):
            with self.subTest(evidence=evidence):
                with mock.patch.object(PREFLIGHT.PROVENANCE, 'regular_bytes', return_value=evidence):
                    with self.assertRaises((KeyError, ValueError, TypeError)):
                        PREFLIGHT.load_candidate(self.root)

    def test_container_copy_requires_one_regular_executable_without_links(self):
        payload, mode = PREFLIGHT.container_payload(archive([regular('gosu', self.payload)]))
        self.assertEqual((payload, mode), (self.payload, 0o755))
        for entries in ([], [link('gosu', '/unexpected')],
                        [link('gosu', 'other', tarfile.LNKTYPE)],
                        [regular('gosu', self.payload), regular('extra', b'unexpected')]):
            with self.subTest(entries=entries):
                with self.assertRaises(ValueError):
                    PREFLIGHT.container_payload(archive(entries))
        with self.assertRaises((ValueError, tarfile.TarError)):
            PREFLIGHT.container_payload(b'unreadable tar evidence')

    def test_cached_image_final_layer_overrides_stale_gosu(self):
        path, image_id = self.image_archive([
            [regular('usr/local/bin/gosu', b'inherited vulnerable gosu')],
            [regular('usr/local/bin/gosu', self.payload)],
        ])
        payload, mode = PREFLIGHT.image_payload(path, image_id)
        PREFLIGHT.validate_payload(payload, mode, self.candidate)

    def test_cached_image_stale_final_layer_fails_reviewed_identity(self):
        path, image_id = self.image_archive([
            [regular('usr/local/bin/gosu', self.payload)],
            [regular('usr/local/bin/gosu', b'inherited vulnerable gosu')],
        ])
        payload, mode = PREFLIGHT.image_payload(path, image_id)
        with self.assertRaises(ValueError):
            PREFLIGHT.validate_payload(payload, mode, self.candidate)

    def test_cached_image_missing_or_whiteouted_gosu_fails(self):
        for final_layer in ([], [regular('usr/local/bin/.wh.gosu')],
                            [regular('usr/local/.wh.bin')],
                            [regular('usr/local/bin/.wh..wh..opq')],
                            [regular('usr/.wh..wh..opq')]):
            with self.subTest(final_layer=final_layer):
                layers = ([final_layer] if not final_layer else
                          [[regular('usr/local/bin/gosu', self.payload)], final_layer])
                path, image_id = self.image_archive(layers)
                with self.assertRaises(ValueError):
                    PREFLIGHT.image_payload(path, image_id)

    def test_whiteout_and_replacement_in_same_layer_retains_replacement(self):
        path, image_id = self.image_archive([
            [regular('usr/local/bin/gosu', b'inherited vulnerable gosu')],
            [regular('usr/local/bin/gosu', self.payload), regular('usr/local/bin/.wh..wh..opq')],
        ])
        payload, mode = PREFLIGHT.image_payload(path, image_id)
        PREFLIGHT.validate_payload(payload, mode, self.candidate)

    def test_cached_image_rejects_links_for_executable_or_ancestors(self):
        for name in ('usr', 'usr/local', 'usr/local/bin', 'usr/local/bin/gosu'):
            for kind in (tarfile.SYMTYPE, tarfile.LNKTYPE):
                with self.subTest(name=name, kind=kind):
                    path, image_id = self.image_archive([
                        [regular('usr/local/bin/gosu', self.payload)],
                        [link(name, '/unexpected', kind)],
                    ])
                    with self.assertRaises(ValueError):
                        PREFLIGHT.image_payload(path, image_id)

    def test_cached_image_rejects_wrong_identity_platform_and_layer_digest(self):
        variants = (
            lambda config: config.update(architecture='arm64'),
            lambda config: config.update(os='windows'),
            lambda config: config['rootfs'].update(diff_ids=['sha256:' + '0' * 64]),
        )
        for update in variants:
            with self.subTest(update=update):
                path, image_id = self.image_archive([[regular('usr/local/bin/gosu', self.payload)]], update)
                with self.assertRaises(ValueError):
                    PREFLIGHT.image_payload(path, image_id)
        path, image_id = self.image_archive([[regular('usr/local/bin/gosu', self.payload)]])
        with self.assertRaises(ValueError):
            PREFLIGHT.image_payload(path, 'sha256:' + '0' * 64)


    def preflight(self, *, container=True, volume=False, cached=False,
                  payload=None, platform='linux/amd64', fail=None):
        commands = []
        payload = self.payload if payload is None else payload
        image_id = 'sha256:' + 'a' * 64
        cached_archive = None
        if cached:
            cached_archive, image_id = self.image_archive([[regular('usr/local/bin/gosu', payload)]])

        def docker(*args, **kwargs):
            commands.append(args)
            if fail and fail(args):
                raise subprocess.CalledProcessError(1, ['docker', *args], stderr=b'synthetic-secret-detail')
            if args[:2] == ('compose', 'ps'):
                return (('c' * 64) + '\n').encode() if container else b''
            if args[0] == 'inspect':
                return (image_id + '\n').encode()
            if args[:2] == ('image', 'inspect'):
                return (platform + '\n').encode()
            if args[0] == 'cp':
                return archive([regular('gosu', payload)])
            if args[:2] == ('volume', 'ls'):
                return b'enterprise_shop_postgres18_volume\n' if volume else b''
            if args[:2] == ('compose', 'config'):
                return b'fixture-postgres\n'
            if args[:2] == ('image', 'ls'):
                return (image_id + '\n').encode() if cached else b''
            if args[0] == 'info':
                return (platform + '\n').encode()
            if args[:2] == ('image', 'save'):
                self.assertIsNotNone(cached_archive)
                shutil.copyfile(cached_archive, args[args.index('--output') + 1])
                return b''
            self.fail(f'Unexpected or mutating Docker command: {args!r}')

        with mock.patch.object(PREFLIGHT, 'load_candidate', return_value=self.candidate), \
                mock.patch.object(PREFLIGHT, 'docker', side_effect=docker):
            state = PREFLIGHT.verify_local_postgres(self.root)
        return state, commands

    def test_existing_container_uses_read_only_copy_for_running_and_stopped_states(self):
        for state in ('running', 'stopped'):
            with self.subTest(state=state):
                result, commands = self.preflight()
                self.assertEqual(result, 'existing')
                self.assertTrue(any(args[0] == 'cp' for args in commands))
                self.assertFalse(any(args[:2] in {('image', 'save'), ('image', 'ls')}
                                     for args in commands))
                self.assertFalse(any(args[0] in {'exec', 'run', 'create', 'start', 'stop', 'rm'}
                                     for args in commands))

    def test_existing_stale_container_fails_without_any_start_or_database_mutation(self):
        with self.assertRaises(ValueError):
            self.preflight(payload=b'inherited vulnerable gosu')

    def test_existing_container_with_unsupported_architecture_fails(self):
        with self.assertRaises(ValueError):
            self.preflight(platform='linux/arm64')

    def test_safe_first_start_with_no_container_image_or_persistent_volume_passes(self):
        result, commands = self.preflight(container=False)
        self.assertEqual(result, 'first-start')
        self.assertTrue(any(args[:2] == ('volume', 'ls') for args in commands))
        self.assertFalse(any(args[0] == 'cp' or args[:2] == ('image', 'save') for args in commands))

    def test_first_start_with_reviewed_cached_image_checks_bytes_without_container_creation(self):
        result, commands = self.preflight(container=False, cached=True)
        self.assertEqual(result, 'first-start')
        self.assertTrue(any(args[:2] == ('image', 'save') for args in commands))
        self.assertFalse(any(args[0] in {'run', 'create', 'start'} for args in commands))

    def test_stale_cached_image_and_detached_persistent_volume_require_operator_review(self):
        with self.assertRaises(ValueError):
            self.preflight(container=False, cached=True, payload=b'inherited vulnerable gosu')
        with self.assertRaises(ValueError):
            self.preflight(container=False, volume=True)
        with self.assertRaises(ValueError):
            self.preflight(container=False, volume=True, cached=True)

    def test_missing_docker_and_unreadable_copy_or_image_evidence_fail_closed(self):
        with mock.patch.object(PREFLIGHT, 'load_candidate', return_value=self.candidate), \
                mock.patch.object(PREFLIGHT, 'docker', side_effect=FileNotFoundError('docker')):
            with self.assertRaises(OSError):
                PREFLIGHT.verify_local_postgres(self.root)
        for predicate in (lambda args: args[0] == 'cp',
                          lambda args: args[:2] == ('compose', 'ps')):
            with self.subTest(predicate=predicate):
                with self.assertRaises(subprocess.CalledProcessError):
                    self.preflight(fail=predicate)
        with self.assertRaises(subprocess.CalledProcessError):
            self.preflight(container=False, cached=True,
                           fail=lambda args: args[:2] == ('image', 'save'))


    def test_first_start_rejects_unsupported_daemon_architecture(self):
        for platform in ('linux/arm64', 'windows/amd64'):
            with self.subTest(platform=platform):
                with self.assertRaises(ValueError):
                    self.preflight(container=False, platform=platform)

    def test_first_start_rejects_missing_build_inputs_or_removed_digest_gate(self):
        (self.root / 'docker/postgres/Dockerfile').write_text('FROM postgres:18-alpine\n')
        with self.assertRaises(ValueError):
            self.preflight(container=False)
        (self.root / 'docker/postgres/gosu/go.sum').unlink()
        with self.assertRaises(ValueError):
            self.preflight(container=False)

    def test_docker_absence_daemon_failure_and_timeout_do_not_disclose_errors(self):
        errors = (FileNotFoundError('synthetic-secret-detail'),
                  subprocess.CalledProcessError(1, ['docker'], stderr=b'synthetic-secret-detail'),
                  subprocess.TimeoutExpired(['docker'], 120))
        for error in errors:
            with self.subTest(error=type(error).__name__):
                with mock.patch.object(PREFLIGHT.subprocess, 'run', side_effect=error) as run:
                    with self.assertRaises(ValueError) as raised:
                        PREFLIGHT.docker('compose', 'ps', '--all', '--quiet', 'postgres', root=self.root)
                self.assertNotIn('synthetic-secret-detail', str(raised.exception))
                self.assertEqual(run.call_args.args[0],
                                 ['docker', 'compose', 'ps', '--all', '--quiet', 'postgres'])
                self.assertIs(run.call_args.kwargs['stderr'], subprocess.DEVNULL)


    def test_corrupt_compressed_image_evidence_has_concise_cli_failure(self):
        errors = io.StringIO()
        with mock.patch.object(PREFLIGHT.sys, 'argv', [str(SCRIPT)]), \
                mock.patch.object(PREFLIGHT, 'verify_local_postgres',
                                  side_effect=PREFLIGHT.zlib.error('Invalid compressed layer')), \
                mock.patch.object(PREFLIGHT.sys, 'stderr', errors):
            self.assertEqual(PREFLIGHT.main(), 1)
        self.assertIn('Invalid compressed layer', errors.getvalue())
        self.assertNotIn('Traceback', errors.getvalue())

    def test_cached_image_with_unsupported_platform_fails_before_archive_read(self):
        with self.assertRaises(ValueError):
            self.preflight(container=False, cached=True, platform='linux/arm64')

    def test_cli_failure_is_actionable_without_claiming_startup_or_leaking_docker_errors(self):
        output, errors = io.StringIO(), io.StringIO()
        with mock.patch.object(PREFLIGHT.sys, 'argv', [str(SCRIPT)]), \
                mock.patch.object(PREFLIGHT, 'verify_local_postgres',
                                  side_effect=ValueError('Installed gosu differs from the reviewed patched executable')), \
                mock.patch.object(PREFLIGHT.sys, 'stdout', output), \
                mock.patch.object(PREFLIGHT.sys, 'stderr', errors):
            self.assertEqual(PREFLIGHT.main(), 1)
        self.assertEqual(output.getvalue(), '')
        self.assertIn('Installed gosu differs', errors.getvalue())
        self.assertIn('No Docker resources were changed', errors.getvalue())
        self.assertIn('docs/operations/local-development.md', errors.getvalue())


class RunDevPreflightTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / 'scripts').mkdir()
        shutil.copyfile(ROOT / 'scripts/run-dev.sh', self.root / 'scripts/run-dev.sh')
        self.commands = self.root / 'commands'
        self.state = self.root / 'role-state'
        self.state.write_text('ready')
        binaries = self.root / 'bin'
        binaries.mkdir()
        (binaries / 'python3').write_text(textwrap.dedent('''\
            #!/bin/sh
            printf 'preflight\\n' >> "$FAKE_COMMANDS"
            [ "$FAKE_PREFLIGHT" != failure ] || exit 1
            printf '%s\\n' "$FAKE_PREFLIGHT"
            '''))
        (binaries / 'docker').write_text(textwrap.dedent('''\
            #!/bin/sh
            [ "$1" = compose ] || exit 99
            shift
            case "$1" in
              up) printf '%s\\n' "$*" >> "$FAKE_COMMANDS" ;;
              exec)
                printf 'role-check\\n' >> "$FAKE_COMMANDS"
                if [ "$(cat "$FAKE_ROLE_STATE")" = ready ]; then printf 'ok\\n'; else printf 'invalid_runtime_role\\n'; fi
                ;;
              run)
                printf '%s\\n' "$*" >> "$FAKE_COMMANDS"
                printf ready > "$FAKE_ROLE_STATE"
                ;;
              *) exit 99 ;;
            esac
            '''))
        for path in binaries.iterdir():
            path.chmod(0o755)
        self.environment = {
            'PATH': str(binaries) + ':/usr/bin:/bin',
            'FAKE_COMMANDS': str(self.commands), 'FAKE_ROLE_STATE': str(self.state),
            'POSTGRES_DB': 'synthetic_database', 'POSTGRES_USER': 'synthetic_owner',
            'POSTGRES_PASSWORD': 'synthetic_owner_password', 'POSTGRES_HOST_PORT': '5433',
            'APP_DB_USER': 'synthetic_runtime', 'APP_DB_PASSWORD': 'synthetic_runtime_password',
        }

    def run_dev(self, result):
        self.environment['FAKE_PREFLIGHT'] = result
        outcome = subprocess.run(['sh', 'scripts/run-dev.sh', '--prepare-only'], cwd=self.root,
                                 env=self.environment, text=True, capture_output=True, check=False)
        return outcome, self.commands.read_text().splitlines() if self.commands.exists() else []

    def test_preflight_failure_prevents_every_compose_and_database_operation(self):
        result, commands = self.run_dev('failure')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(commands, ['preflight'])
        self.assertEqual(self.state.read_text(), 'ready')
        self.assertNotIn('synthetic_owner_password', result.stdout + result.stderr)
        self.assertNotIn('synthetic_runtime_password', result.stdout + result.stderr)

    def test_unexpected_preflight_state_prevents_compose_and_database_operations(self):
        result, commands = self.run_dev('unexpected-result')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(commands, ['preflight'])
        self.assertEqual(self.state.read_text(), 'ready')

    def test_existing_verified_container_is_retained_before_role_check(self):
        result, commands = self.run_dev('existing')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(commands[0], 'preflight')
        self.assertIn('--no-recreate', commands[1].split())
        self.assertIn('--no-build', commands[1].split())
        self.assertIn('--wait', commands[1].split())
        self.assertEqual(commands[2:], ['role-check'])
        self.assertEqual(self.state.read_text(), 'ready')

    def test_first_start_still_waits_and_existing_valid_role_skips_bootstrap(self):
        result, commands = self.run_dev('first-start')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(commands[0], 'preflight')
        self.assertIn('--no-recreate', commands[1].split())
        self.assertIn('--wait', commands[1].split())
        self.assertEqual(commands[2:], ['role-check'])

    def test_existing_runtime_role_repair_remains_one_shot_and_is_rechecked(self):
        self.state.write_text('unsafe_attributes_or_missing_grants')
        result, commands = self.run_dev('existing')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(commands[0], 'preflight')
        self.assertEqual(commands[2], 'role-check')
        self.assertEqual(commands[3], 'run --rm --no-deps --build database-role-bootstrap')
        self.assertEqual(commands[4:], ['role-check'])
        self.assertEqual(self.state.read_text(), 'ready')


if __name__ == '__main__':
    unittest.main()
