import importlib.util
import copy
import io
import json
import os
import subprocess
import tarfile
import tempfile
import threading
import unittest
from pathlib import Path
from datetime import datetime, timedelta
from types import SimpleNamespace
from test_go_advisory_acquisition import NOW, MODIFIED, fixture
from unittest.mock import patch

ROOT = Path(__file__).parents[2]
SPEC = importlib.util.spec_from_file_location('trivy_builder', ROOT / 'scripts/build-trivy-candidate.py')
BUILD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUILD)
SPEC = importlib.util.spec_from_file_location('trivy_compatibility', ROOT / 'scripts/verify-trivy-candidate-compatibility.py')
COMPAT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(COMPAT)
SPEC = importlib.util.spec_from_file_location('trivy_evidence', ROOT / 'scripts/trivy_candidate_evidence.py')
EVIDENCE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(EVIDENCE)


class TrivyBuildTest(unittest.TestCase):
    def test_scanner_runtime_stays_outside_the_maven_source_tree(self):
        with patch.dict('os.environ', {'RUNNER_TEMP': '/tmp/runner-proof'}):
            self.assertEqual(Path('/tmp/runner-proof/enterprise-shop-trivy'), BUILD.runtime_directory())
        with patch.dict('os.environ', {'RUNNER_TEMP': str(ROOT / '.tmp')}):
            with self.assertRaisesRegex(ValueError, 'outside the checkout'):
                BUILD.runtime_directory()

    def test_private_read_only_module_cache_can_be_removed(self):
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory) / 'cache'
            (cache / 'modules').mkdir(parents=True)
            (cache / 'modules/go.mod').write_text('module fixture')
            (cache / 'modules/go.mod').chmod(0o444)
            (cache / 'modules').chmod(0o555)
            BUILD.remove_cache(cache)
            self.assertFalse(cache.exists())

    def test_modified_upstream_archive_is_rejected_before_extraction(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'source'
            with self.assertRaisesRegex(ValueError, 'upstream source archive'):
                BUILD.prepare(b'altered archive', source)
            self.assertFalse(source.exists())

    def test_image_is_reproducible_and_contains_only_reviewed_runtime_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory)
            (source / 'contrib').mkdir()
            (source / 'contrib/test.tpl').write_text('template')
            first = BUILD.image_archive(b'ELF fixture', b'CA fixture', source)
            repeat = BUILD.image_archive(b'ELF fixture', b'CA fixture', source)
            self.assertEqual(first, repeat)
            ref, image_id, config, layer, archive, census = first
            self.assertEqual(image_id, 'sha256:' + BUILD.sha(config))
            self.assertEqual(ref, 'local/enterprise-shop-trivy:sha256-' + BUILD.sha(config))
            self.assertEqual({'usr/local/bin/trivy', 'etc/ssl/certs/ca-certificates.crt', 'contrib/test.tpl', 'tmp'}, set(census))
            self.assertEqual({'type': 'directory', 'mode': 0o1777}, census['tmp'])
            parsed = json.loads(config)
            self.assertEqual(['/usr/local/bin/trivy'], parsed['config']['Entrypoint'])
            self.assertEqual(['sha256:' + BUILD.sha(layer)], parsed['rootfs']['diff_ids'])
            with tarfile.open(fileobj=io.BytesIO(archive)) as saved:
                manifest = json.load(saved.extractfile('manifest.json'))
                self.assertEqual(config, saved.extractfile(manifest[0]['Config']).read())
                self.assertEqual(layer, saved.extractfile(manifest[0]['Layers'][0]).read())

    def test_binary_changes_change_image_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory)
            one = BUILD.image_archive(b'one', b'ca', source)
            two = BUILD.image_archive(b'two', b'ca', source)
            self.assertNotEqual(one[1], two[1])

    def test_source_census_distinguishes_fixture_links_without_following_them(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory)
            (source / 'fixture').symlink_to('absent')
            self.assertEqual({'fixture': BUILD.sha(b'symlink:absent')}, BUILD.source_files(source))

    def test_auxiliary_copy_retains_complete_upstream_fixture_link_bytes(self):
        spec = importlib.util.spec_from_file_location('scan_auxiliary', ROOT / 'scripts/scan-auxiliary-containers.py')
        scan = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(scan)
        with tempfile.TemporaryDirectory() as directory:
            source, target = Path(directory) / 'source', Path(directory) / 'target'
            source.mkdir()
            (source / 'dangling-fixture').symlink_to('absent')
            (source / 'file').write_text('upstream bytes')
            (source / 'linked-fixture').symlink_to('file')
            expected = BUILD.source_files(source)
            scan.copy_candidate_evidence(source, target)
            self.assertEqual(expected, BUILD.source_files(target))
            self.assertTrue((target / 'dangling-fixture').is_symlink())

    def test_empty_package_report_is_not_compatible_clean_output(self):
        with self.assertRaisesRegex(ValueError, 'package inventory'):
            COMPAT.packages({'SchemaVersion': 2, 'Trivy': {'Version': '0.75.0'}, 'Results': []})

    def test_stale_or_manipulated_database_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'metadata.json'
            for value in ({'Version': 2, 'UpdatedAt': '2026-01-01T00:00:00Z', 'NextUpdate': '2026-01-02T00:00:00Z',
                           'DownloadedAt': '2026-01-01T00:00:00Z'},
                          {'Version': 2, 'UpdatedAt': '2099-01-01T00:00:00Z', 'NextUpdate': '2099-01-02T00:00:00Z',
                           'DownloadedAt': '2099-01-01T00:00:00Z'}):
                path.write_text(json.dumps(value))
                with self.assertRaisesRegex(ValueError, 'database'):
                    COMPAT.database(path)

    def test_selected_scanner_requires_independent_build_authority(self):
        contract = json.loads((ROOT / '.github/security/auxiliary-container-scope.json').read_text())
        self.assertEqual(EVIDENCE.METHOD, contract['scanner_independent_integrity'])
        self.assertTrue(contract['images']['scanner']['governed'])
        self.assertTrue(contract['images']['scanner']['candidate_build'])
        builder = contract['images']['scanner-builder']
        self.assertEqual(BUILD.BUILDER, builder['reference'])
        self.assertEqual(BUILD.BUILDER_BINARY, builder['identity']['sha256'])
        self.assertTrue(builder['governed'])
        self.assertEqual(['HIGH', 'CRITICAL'], builder['threshold'])


class TrivyRuntimeEnvironmentTest(unittest.TestCase):
    def test_reviewed_docker29_settings_are_accepted_in_either_order(self):
        reviewed = {'GOMEMLIMIT': '7GiB', 'GOMAXPROCS': '1'}
        for values in (['GOMEMLIMIT=7GiB', 'GOMAXPROCS=1', None],
                       ['GOMAXPROCS=1', 'GOMEMLIMIT=7GiB', None]):
            with self.subTest(values=values):
                BUILD.validate_runtime_environment(values, reviewed)

    def test_duplicate_keys_are_rejected_even_when_values_agree(self):
        reviewed = {'GOMEMLIMIT': '7GiB', 'GOMAXPROCS': '1'}
        for values in (['GOMEMLIMIT=7GiB', 'GOMEMLIMIT=7GiB', None],
                       ['GOMEMLIMIT=7GiB', 'GOMEMLIMIT=off', None],
                       ['GOMAXPROCS=1', 'GOMAXPROCS=1', None],
                       ['GOMAXPROCS=1', 'GOMAXPROCS=2', None]):
            with self.subTest(values=values), self.assertRaises(ValueError):
                BUILD.validate_runtime_environment(values, reviewed)

    def test_missing_wrong_extra_and_malformed_settings_are_rejected(self):
        reviewed = {'GOMEMLIMIT': '7GiB', 'GOMAXPROCS': '1'}
        for values in (None, {}, (), [], [None], ['GOMEMLIMIT=7GiB', None],
                       ['GOMEMLIMIT=7GiB', 'GOMAXPROCS=1'],
                       [None, 'GOMEMLIMIT=7GiB', 'GOMAXPROCS=1'],
                       ['GOMEMLIMIT=off', 'GOMAXPROCS=1', None],
                       ['GOMEMLIMIT=7GiB', 'GOGC=off', None],
                       ['GOMEMLIMIT=7GiB', 'GODEBUG=x', None],
                       ['GOMEMLIMIT=7GiB', 'GOFLAGS=-tags=exclude', None],
                       ['GOMEMLIMIT=7GiB', 'PATH=/unreviewed', None],
                       ['GOMEMLIMIT=7GiB', 'GOMAXPROCS=1', 'GOGC=off', None],
                       ['GOMEMLIMIT=7GiB', None, None],
                       ['GOMEMLIMIT=7GiB', {'GOMAXPROCS': '1'}, None],
                       ['GOMEMLIMIT=7GiB', ' GOMAXPROCS=1', None],
                       ['GOMEMLIMIT=7GiB', 'GOMAXPROCS=1 ', None],
                       ['GOMEMLIMIT=7GiB', 'GOMAXPROCS', None],
                       ['GOMEMLIMIT=7GiB', 'GOMAXPROCS=1=2', None]):
            with self.subTest(values=values), self.assertRaises(ValueError):
                BUILD.validate_runtime_environment(values, reviewed)

    def test_interim_contract_rejects_any_concurrency_override(self):
        reviewed = {'GOMEMLIMIT': '7GiB'}
        BUILD.validate_runtime_environment(['GOMEMLIMIT=7GiB', None], reviewed)
        for value in ('1', '2', '8'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                BUILD.validate_runtime_environment(['GOMEMLIMIT=7GiB', 'GOMAXPROCS=' + value, None], reviewed)


class TrivyTerminationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        (self.directory / 'cache-analysis').mkdir()
        (self.directory / 'govulncheck').write_bytes(b'analyzer fixture')
        self.head, self.identifier = 'a' * 40, 'b' * 64
        self.name = 'enterprise-shop-trivy-analysis-' + 'c' * 32
        self.status, self.oom, self.completed = 0, False, False
        self.commands, self.observed = [], threading.Event()
        self.fail_collect, self.fail_cleanup, self.wrong_container = False, False, False
        self.analyzer = ['docker', 'run', '--rm', '--memory', '10g', '--cpus', '2', '--pids-limit', '512',
                         '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
                         BUILD.BUILDER, '/evidence/govulncheck']
        for target, side in [(patch.object(BUILD, 'diagnostic_command'), self.probe),
                             (patch.object(BUILD.RYUK.ADVISORY, 'collect'), self.collect)]:
            mocked = target.start()
            self.addCleanup(target.stop)
            mocked.side_effect = side
        fixed = patch.object(BUILD.uuid, 'uuid4', return_value=SimpleNamespace(hex='c' * 32))
        fixed.start()
        self.addCleanup(fixed.stop)

    def probe(self, command):
        self.commands.append(command)
        if command[0] == 'inspect':
            self.observed.set()
            return json.dumps({'id': self.identifier, 'name': '/' + self.name, 'head':
                'd' * 40 if self.wrong_container else self.head, 'image': BUILD.BUILDER,
                'memory': 10 * 1024 ** 3, 'swap': 20 * 1024 ** 3, 'cpus': 2 * 10 ** 9,
                'pids': 512, 'readonly': True, 'runtime_env': ['GOMEMLIMIT=7GiB', None],
                'state': {'status': 'exited' if self.completed else 'running', 'exit': self.status,
                          'oom': self.oom, 'pid': 0, 'started': '', 'finished': ''}}).encode()
        if command[0] == 'events':
            return (json.dumps({'id': self.identifier, 'action': 'oom' if self.oom else 'die',
                               'time': 1, 'signal': '', 'exit': str(self.status)}) + '\n').encode()
        if self.fail_cleanup:
            raise subprocess.CalledProcessError(1, ['docker', 'rm'])
        self.assertEqual(['rm', '--force', self.identifier], command)
        return b''

    def collect(self, command, args, directory, mode, timeout):
        Path(command[7]).write_text(self.identifier)
        self.assertTrue(self.observed.wait(2))
        self.assertNotIn('--rm', command)
        self.assertEqual(BUILD.source_memory_command(self.analyzer)[3:], command[8:])
        self.assertEqual(['-mode=source', '-json', './cmd/trivy'], args)
        self.assertEqual(('source', 1800), (mode, timeout))
        self.completed = True
        if self.fail_collect:
            raise subprocess.TimeoutExpired('fixture', 1800)
        (directory / 'source-govulncheck.json').write_bytes(b'analysis fixture')
        (directory / 'source-govulncheck.log').write_bytes(b'')
        raw = json.dumps({'started_at': BUILD.RYUK.ADVISORY.utc_now(),
                          'completed_at': BUILD.RYUK.ADVISORY.utc_now()}).encode()
        (directory / 'source-go-acquisition.json').write_bytes(raw)
        return {'status': self.status, 'sha256': BUILD.sha(b'analysis fixture'), 'acquisition_sha256': BUILD.sha(raw)}

    def run_analysis(self):
        value = BUILD.source_analysis(self.analyzer, ['-mode=source', '-json', './cmd/trivy'],
                                      self.directory, self.head)
        self.receipt = {'analysis': {'source': value}, 'tool_sha256': BUILD.sha(b'analyzer fixture')}
        return value

    def validate(self):
        return BUILD.validate_source_diagnostics(self.directory, self.receipt, self.head)

    def save(self, report):
        raw = json.dumps(report).encode()
        (self.directory / 'source-termination.json').write_bytes(raw)
        self.receipt['analysis']['source']['diagnostics_sha256'] = BUILD.sha(raw)

    def test_success_preserves_analyzer_command_and_cleans_exact_owned_container(self):
        self.assertEqual(0, self.run_analysis()['status'])
        report = self.validate()
        self.assertTrue(report['cleanup'])
        self.assertFalse(report['container']['state']['oom'])
        self.assertIn(['rm', '--force', self.identifier], self.commands)

    def test_interim_memory_profile_preserves_default_source_concurrency(self):
        command = BUILD.source_memory_command(self.analyzer)
        self.assertEqual(self.analyzer[:-2] + ['-e', 'GOMEMLIMIT=7GiB'] + self.analyzer[-2:], command)
        self.assertEqual('7GiB', BUILD.SOURCE_GOMEMLIMIT)

    def test_runtime_overrides_and_changed_hard_limits_are_rejected(self):
        for extra in (['-e', 'GOMEMLIMIT=off'], ['--env', 'GOGC=off'], ['-e', 'GOMAXPROCS=8'],
                      ['-e', 'GODEBUG=x'], ['-e', 'GOFLAGS=-tags=exclude'],
                      ['--env=GOMEMLIMIT=off'], ['-eGOMEMLIMIT=off'], ['--env-file', '/tmp/env']):
            with self.subTest(extra=extra), self.assertRaisesRegex(ValueError, 'runtime override|environment option'):
                BUILD.source_memory_command(self.analyzer[:-2] + extra + self.analyzer[-2:])
        for flag, value in [('--memory', '20g'), ('--cpus', '4'), ('--pids-limit', '1024')]:
            command = self.analyzer.copy()
            command[command.index(flag) + 1] = value
            with self.assertRaisesRegex(ValueError, 'resource contract'):
                BUILD.source_memory_command(command)
        for extra in (['--memory=20g'], ['-m', '20g'], ['-m20g'], ['--cpus=4'], ['--pids-limit=1024']):
            with self.subTest(extra=extra), self.assertRaisesRegex(ValueError, 'resource contract'):
                BUILD.source_memory_command(self.analyzer[:-2] + extra + self.analyzer[-2:])

    def test_137_diagnostics_distinguish_oom_from_non_oom_without_changing_status(self):
        self.status = 137
        for oom in (True, False):
            with self.subTest(oom=oom):
                self.oom = oom
                self.assertEqual(137, self.run_analysis()['status'])
                report = self.validate()
                self.assertIs(report['container']['state']['oom'], oom)
                self.assertEqual('oom' if oom else 'die', report['events'][0]['action'])

    def test_timeout_removes_old_success_and_retains_cleanup_evidence(self):
        self.run_analysis()
        self.fail_collect = True
        with self.assertRaises(subprocess.TimeoutExpired):
            self.run_analysis()
        self.assertFalse((self.directory / 'source-go-acquisition.json').exists())
        report = json.loads((self.directory / 'source-termination.json').read_text())
        self.assertIsNone(report['analysis'])
        self.assertTrue(report['cleanup'])
        with self.assertRaises((ValueError, KeyError)):
            self.validate()

    def test_cleanup_failure_cannot_be_successful_diagnostic_evidence(self):
        self.fail_cleanup = True
        self.run_analysis()
        with self.assertRaisesRegex(ValueError, 'Incomplete'):
            self.validate()

    def test_oom_evidence_cannot_authorize_an_apparent_zero_exit(self):
        self.oom = True
        self.run_analysis()
        with self.assertRaisesRegex(ValueError, 'Incomplete'):
            self.validate()

    def test_unrelated_container_is_rejected_and_never_removed(self):
        self.wrong_container = True
        self.run_analysis()
        self.assertFalse(any(c[0] == 'rm' for c in self.commands))
        with self.assertRaises(KeyError):
            self.validate()

    def test_missing_malformed_oversized_and_tampered_evidence_is_rejected(self):
        self.run_analysis()
        path = self.directory / 'source-termination.json'
        original = path.read_bytes()
        for payload in (None, b'{', original + b' ', b' ' * (1024 * 1024 + 1)):
            with self.subTest(payload_size=None if payload is None else len(payload)):
                if payload is None:
                    path.unlink()
                else:
                    path.write_bytes(payload)
                with self.assertRaises((OSError, ValueError)):
                    self.validate()
        path.write_bytes(original)

    def test_rebound_stale_unrelated_or_incomplete_diagnostics_are_rejected(self):
        self.run_analysis()
        original = json.loads((self.directory / 'source-termination.json').read_text())
        for mutate in (lambda r: r.update(repository_head='d' * 40),
                       lambda r: r.update(completed_at='2026-01-01T00:00:00Z'),
                       lambda r: r['events'][0].update(id='d' * 64),
                       lambda r: r.update(samples=[]),
                       lambda r: r['samples'][0].update(cache={}),
                       lambda r: r['container'].update(memory=20 * 1024 ** 3),
                       lambda r: r['container'].update(runtime_env=['GOMEMLIMIT=off', None]),
                       lambda r: r['container'].update(runtime_env=['GOMEMLIMIT=7GiB', 'GOMEMLIMIT=7GiB', None]),
                       lambda r: r['container'].update(runtime_env=['GOMEMLIMIT=7GiB', 'GOMAXPROCS=1', None]),
                       lambda r: r['container'].update(runtime_env=['GOMEMLIMIT=7GiB', 'GOMAXPROCS=2', None]),
                       lambda r: r['container']['state'].update(exit=137),
                       lambda r: r.update(stdout_sha256='0' * 64)):
            report = copy.deepcopy(original)
            mutate(report)
            self.save(report)
            with self.assertRaises((ValueError, KeyError)):
                self.validate()


class TrivyActualImageIdentityTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.target = self.root / 'actual.tar'
        self.proof = self.root / 'identity.tar'
        self.fixture = ROOT / 'scripts/tests/fixtures/docker-identity/docker29-image.tar.gz'
        self.config_id = 'sha256:5069b87377ce91b47890d84793e8f68efe42e927f0b423fe958b7c428ef30379'
        self.descriptor_id = 'sha256:22a761becced308c3ef0e30f3a73ca236012b1f02a9109ddb2d2f418dd91a8ff'
        self.observed = {'Id': self.descriptor_id, 'Os': 'linux', 'Architecture': 'amd64', 'Size': 65536}
        self.corrupt = None
        self.commands = []

    def export(self, command, **kwargs):
        self.commands.append(command)
        output = Path(command[command.index('-o') + 1])
        if command[1] != 'save' or self.corrupt is None:
            output.write_bytes(self.fixture.read_bytes())
            return subprocess.CompletedProcess(command, 0)
        with tarfile.open(self.fixture) as source:
            files = {m.name: source.extractfile(m).read() for m in source if m.isfile()}
        name = ('blobs/sha256/' + self.config_id.split(':')[1] if self.corrupt == 'config'
                else json.loads(files['manifest.json'])[0]['Layers'][0])
        files[name] += b'changed'
        with tarfile.open(output, 'w') as saved:
            for path, data in files.items():
                member = tarfile.TarInfo(path)
                member.size = len(data)
                saved.addfile(member, io.BytesIO(data))
        return subprocess.CompletedProcess(command, 0)

    def collect(self):
        with patch.object(COMPAT.subprocess, 'check_output', return_value=json.dumps([self.observed]).encode()), \
                patch.object(COMPAT.subprocess, 'run', side_effect=self.export):
            return COMPAT.export_actual_image('mutable:tag', self.target, self.proof)

    def test_docker29_descriptor_is_bound_to_original_configuration_and_scan_archive(self):
        config, identity = self.collect()
        self.assertEqual(self.config_id, config)
        self.assertNotEqual(self.descriptor_id, config)
        self.assertEqual({'docker_id': self.descriptor_id, 'proof': 'identity.tar'}, identity)
        self.assertEqual(config, EVIDENCE.BINARY.configuration_evidence(self.proof, self.descriptor_id))
        exports = [c for c in self.commands if c[1] == 'save']
        self.assertEqual(1, len(exports))
        self.assertEqual(self.descriptor_id, exports[0][-1])
        self.assertNotIn('mutable:tag', exports[0])

    def test_classic_configuration_identity_remains_accepted(self):
        self.observed['Id'] = self.config_id
        config, identity = self.collect()
        self.assertEqual(self.config_id, config)
        self.assertEqual(self.config_id, identity['docker_id'])

    def test_unrelated_descriptor_cannot_authorize_the_archive(self):
        self.observed['Id'] = 'sha256:' + '0' * 64
        with self.assertRaisesRegex(ValueError, 'inspected immutable image'):
            self.collect()
        self.assertFalse(self.target.exists())

    def test_changed_configuration_or_layer_in_actual_scan_export_is_rejected(self):
        for corruption in ('config', 'layer'):
            self.corrupt = corruption
            with self.subTest(corruption=corruption), self.assertRaises((ValueError, OSError)):
                self.collect()

    def test_retained_metadata_cannot_authorize_a_different_descriptor(self):
        self.collect()
        with self.assertRaisesRegex(ValueError, 'inspected immutable image'):
            EVIDENCE.BINARY.configuration_evidence(self.proof, 'sha256:' + '0' * 64)


class TrivyCompatibilityStorageTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.build = self.root / 'build'
        self.build.mkdir()
        self.candidate = b'candidate fixture'
        self.reference = b'reference fixture'
        self.failure = None
        self.commands = []
        self.runs = []
        source = self.root / 'source'
        source.mkdir()
        archive = BUILD.image_archive(self.candidate, b'CA', source)[4]
        self.actual_image = BUILD.image_archive(b'actual fixture', b'CA', source)
        (self.build / 'image.tar').write_bytes(archive)
        COMPAT.write(self.build / 'build-receipt.json', {'image_reference': 'local/candidate:fixture',
            'binary_sha256': COMPAT.sha(self.candidate), 'repeat_sha256': COMPAT.sha(self.candidate),
            'go_version': '1.27.3', 'repository_head': 'fixture-head'})
        for target, replacement in ((patch.object(COMPAT.subprocess, 'run'), self.run_command),
                                    (patch.object(COMPAT.subprocess, 'check_output'), self.output),
                                    (patch.object(COMPAT, 'REFERENCE_BINARY', COMPAT.sha(self.reference)), None),
                                    (patch.object(COMPAT.shutil, 'disk_usage'), (20 * 1024 ** 3,) * 3)):
            mocked = target.start()
            self.addCleanup(target.stop)
            if callable(replacement):
                mocked.side_effect = replacement
            elif isinstance(replacement, tuple):
                mocked.return_value = COMPAT.shutil._ntuple_diskusage(*replacement)

    def output(self, command, **kwargs):
        if command[0] == 'git':
            return 'fixture-head'
        if command[1] == 'create':
            return 'fixture-container'
        return json.dumps([{'Id': self.actual_image[1], 'Os': 'linux', 'Architecture': 'amd64',
                            'Size': len(self.actual_image[3])}]).encode()

    def run_command(self, command, **kwargs):
        self.commands.append(command)
        if command[1] == 'cp':
            Path(command[-1]).write_bytes(self.reference)
        elif command[1] == 'save' or command[1:3] == ['image', 'save']:
            Path(command[command.index('-o') + 1]).write_bytes(self.actual_image[4])
        elif command[1] == 'run':
            self.runs.append(command)
            mounts = {value.split(':')[1]: Path(value.split(':')[0])
                      for index, value in enumerate(command) if index and command[index - 1] == '-v'}
            if '--download-db-only' in command:
                for name in ('/tmp', '/cache'):
                    self.assertEqual([], list(mounts[name].iterdir()))
                    self.assertEqual(os.getuid(), mounts[name].stat().st_uid)
                    self.assertEqual(os.getgid(), mounts[name].stat().st_gid)
                    self.assertEqual(0o700, mounts[name].stat().st_mode & 0o777)
                (mounts['/tmp'] / 'partial').write_bytes(b'staging')
                if self.failure:
                    kwargs['stdout'].write('no space left on device\n')
                    raise subprocess.CalledProcessError(1, command)
                db = mounts['/cache'] / 'db'
                db.mkdir()
                (db / 'trivy.db').write_bytes(b'fresh database')
                now = COMPAT.datetime.now(COMPAT.timezone.utc)
                COMPAT.write(db / 'metadata.json', {'Version': 2, 'UpdatedAt': now.isoformat(),
                    'DownloadedAt': now.isoformat(), 'NextUpdate': (now + timedelta(hours=6)).isoformat()})
            elif '--download-java-db-only' in command:
                pass
            elif '--output' in command:
                output = mounts['/evidence'] / Path(command[command.index('--output') + 1]).name
                if 'convert' in command:
                    COMPAT.write(output, {'bomFormat': 'CycloneDX', 'specVersion': '1.7',
                        'components': [{'purl': 'pkg:generic/fixture@1'}]})
                else:
                    name = output.name.removesuffix('.raw.json')
                    findings = COMPAT.EXPECTED if name == 'reference-rootfs' else (
                        {('CVE-2025-48734', 'commons-beanutils:commons-beanutils', '1.9.4', 'HIGH')}
                        if name == 'vulnerable' else set())
                    COMPAT.write(output, {'SchemaVersion': 2, 'Trivy': {'Version': '0.75.0'},
                        'Metadata': {'ImageID': self.actual_image[1]}, 'Results': [{
                            'Packages': [{'Identifier': {'PURL': 'pkg:generic/fixture@1'}}],
                            'Vulnerabilities': [dict(zip(('VulnerabilityID', 'PkgName', 'InstalledVersion', 'Severity'), v))
                                                for v in findings]}]})
            else:
                return subprocess.CompletedProcess(command, 1)
        return subprocess.CompletedProcess(command, 0)

    def assert_cleaned(self, directory):
        for name in ('inputs', 'cache', 'temporary'):
            self.assertFalse((directory / name).exists())

    def test_both_paths_use_private_disk_with_unchanged_container_boundaries(self):
        for images in ([], ['local/actual:fixture']):
            with self.subTest(images=images):
                self.runs.clear()
                directory = self.root / ('actual' if images else 'reference')
                self.assertTrue(COMPAT.collect(self.build, directory, images)['verified'])
                storage = COMPAT.read(directory / 'scratch-storage.json')
                self.assertEqual((8 if images else 2) * 1024 ** 3, storage['minimum_free_bytes'])
                for command in self.runs:
                    self.assertNotIn('--tmpfs', command)
                    self.assertIn(f'{directory}/temporary:/tmp', command)
                    self.assertIn(f'{directory}/inputs:/input:ro', command)
                    mounts = {value.split(':')[1]: value.split(':')[0]
                              for index, value in enumerate(command) if index and command[index - 1] == '-v'}
                    self.assertEqual({'/tmp', '/input', '/cache', '/evidence'}, set(mounts))
                    self.assertIn(mounts['/cache'], {str(directory / 'cache'),
                        str(directory / 'reference/empty-cache'), str(directory / 'candidate/empty-cache')})
                    for option, value in (('--user', f'{os.getuid()}:{os.getgid()}'), ('--cap-drop', 'ALL'),
                                          ('--security-opt', 'no-new-privileges'), ('--pids-limit', '128'),
                                          ('--memory', '4g'), ('--cpus', '2')):
                        self.assertEqual(value, command[command.index(option) + 1])
                    self.assertIn('--read-only', command)
                    self.assertNotIn('/var/run/docker.sock', ' '.join(command))
                    if '--download-db-only' not in command and '--download-java-db-only' not in command:
                        self.assertEqual('none', command[command.index('--network') + 1])
                self.assert_cleaned(directory)

    def test_low_capacity_blocks_before_database_execution_and_cleans_private_state(self):
        for images in ([], ['local/actual:fixture']):
            directory = self.root / ('low-actual' if images else 'low-reference')
            with patch.object(COMPAT.shutil, 'disk_usage', return_value=COMPAT.shutil._ntuple_diskusage(1, 1, 0)):
                with self.assertRaisesRegex(ValueError, 'Insufficient.*free runner disk space'):
                    COMPAT.collect(self.build, directory, images)
            self.assertFalse(self.runs)
            self.assertFalse((directory / 'result.json').exists())
            self.assertTrue((directory / 'scratch-storage.json').exists())
            self.assert_cleaned(directory)

    def test_download_exhaustion_is_secret_free_and_the_next_run_has_fresh_state(self):
        directory = self.root / 'failed'
        self.failure = True
        with patch.dict(os.environ, {'TRIVY_DB_REPOSITORY': 'https://registry.invalid/db?token=secret-fixture'}):
            with self.assertRaisesRegex(ValueError, 'database-update.log.*free runner disk space') as failure:
                COMPAT.collect(self.build, directory)
        self.assertNotIn('secret-fixture', str(failure.exception))
        self.assertFalse((directory / 'result.json').exists())
        self.assertIn('no space left', (directory / 'database-update.log').read_text())
        self.assert_cleaned(directory)
        self.failure = False
        self.assertTrue(COMPAT.collect(self.build, self.root / 'fresh')['verified'])

    def test_existing_directory_is_rejected_without_deleting_another_runs_data(self):
        directory = self.root / 'existing'
        directory.mkdir()
        marker = directory / 'temporary'
        marker.mkdir()
        (marker / 'other-run').write_text('keep')
        with self.assertRaisesRegex(ValueError, 'already exists'):
            COMPAT.collect(self.build, directory)
        self.assertEqual('keep', (marker / 'other-run').read_text())
        self.assertFalse(self.commands)

    def test_early_invalid_build_cleans_inputs_without_emitting_success(self):
        (self.build / 'image.tar').write_bytes(b'malformed archive')
        directory = self.root / 'invalid'
        with self.assertRaises(tarfile.ReadError):
            COMPAT.collect(self.build, directory)
        self.assert_cleaned(directory)
        self.assertFalse((directory / 'result.json').exists())

    def test_uploaded_evidence_excludes_private_storage_in_both_paths(self):
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        for directory in ('trivy-compatibility', 'trivy-actual-compatibility'):
            for name in ('inputs', 'cache', 'temporary'):
                self.assertIn(f'!.tmp/{directory}/{name}/**', workflow)


class TrivyTrustContractTest(unittest.TestCase):
    def setUp(self):
        self.candidate = {'image_id': 'sha256:' + 'a' * 64,
            'image_reference': 'local/enterprise-shop-trivy:sha256-' + 'a' * 64,
            'binary_sha256': 'b' * 64, 'repeat_sha256': 'b' * 64, 'go_version': '1.27.3',
            'exceptions': [], 'applicability': [], 'builder': BUILD.BUILDER,
            'builder_image_id': BUILD.BUILDER_ID, 'builder_manifest': BUILD.BUILDER_MANIFEST,
            'builder_binary_sha256': BUILD.BUILDER_BINARY, 'sdk_sha256': BUILD.SDK_SHA, 'sdk_tools': BUILD.SDK_TOOLS,
            'upstream_source_sha': BUILD.SOURCE, 'upstream_archive_sha256': BUILD.ARCHIVE_SHA,
            'patch_sha256': BUILD.PATCH_SHA, 'go_mod_sha256': BUILD.GO_MOD_SHA, 'go_sum_sha256': BUILD.GO_SUM_SHA,
            'flags': BUILD.FLAGS, 'upx': False, 'independent_cold_caches': True,
            'buildinfo': {'go_version': '1.27.3', 'path': 'github.com/aquasecurity/trivy/cmd/trivy',
                'main_module': 'github.com/aquasecurity/trivy', 'main_version': '(devel)',
                'dependencies': {'golang.org/x/net': {'version': 'v0.62.0',
                    'sum': 'h1:18ELalfM5+XNRwzM2nYqXsZQ9ByS1uhj2O/U7+5jqRY='},
                    'github.com/go-jose/go-jose/v4': {'version': 'v4.1.6',
                    'sum': 'h1:sNRkftxFJxwsILiQukqp5z83QBSmntVwmgRY7AqBDO4='}},
                'build': {'CGO_ENABLED': '0', 'GOARCH': 'amd64', 'GOOS': 'linux', 'GOAMD64': 'v1',
                          '-buildmode': 'exe', '-trimpath': 'true'}}}
        self.image = {'reference': self.candidate['image_reference'], 'go_version': '1.27.3',
                      'identity': {'sha256': self.candidate['binary_sha256']}, 'exceptions': [], 'applicability': []}

    def validate(self):
        EVIDENCE.inventory(self.image, self.candidate)

    def test_reviewed_source_compiler_and_module_identities_are_accepted(self):
        self.validate()

    def test_known_vulnerable_official_executable_cannot_be_selected(self):
        self.candidate['binary_sha256'] = self.candidate['repeat_sha256'] = COMPAT.REFERENCE_BINARY
        self.image['identity']['sha256'] = COMPAT.REFERENCE_BINARY
        with self.assertRaisesRegex(ValueError, 'vulnerable'):
            self.validate()

    def test_vulnerable_compiler_or_net_module_is_rejected(self):
        for mutate in (lambda v: v['buildinfo'].update(go_version='1.27.1'),
                       lambda v: v['buildinfo'].update(go_version='1.27.2'),
                       lambda v: v['buildinfo']['dependencies']['golang.org/x/net'].update(version='v0.59.0'),
                       lambda v: v['buildinfo']['dependencies']['golang.org/x/net'].update(version='v0.60.0'),
                       lambda v: v['buildinfo']['dependencies']['golang.org/x/net'].update(version='v0.61.0'),
                       lambda v: v['buildinfo']['dependencies']['golang.org/x/net'].update(
                           sum='h1:79p50tfZlm0J9YfoDsSi639qSXNGVwEzOPLCxM2FsYU=')):
            old = copy.deepcopy(self.candidate)
            mutate(self.candidate)
            with self.assertRaisesRegex(ValueError, 'vulnerable'):
                self.validate()
            self.candidate = old

    def test_vulnerable_go_jose_or_unverified_module_sum_is_rejected(self):
        for values in ({'version': 'v4.1.5'}, {'sum': 'h1:unverified'}):
            old = copy.deepcopy(self.candidate)
            self.candidate['buildinfo']['dependencies']['github.com/go-jose/go-jose/v4'].update(values)
            with self.subTest(values=values), self.assertRaisesRegex(ValueError, 'vulnerable'):
                self.validate()
            self.candidate = old

    def test_raw_inventory_requires_the_patched_actual_standard_library(self):
        metadata = self.candidate['buildinfo']
        report = {'Results': [{'Target': 'usr/local/bin/trivy', 'Type': 'gobinary',
            'Packages': [{'Name': name, 'Version': version} for name, version in
                [(metadata['main_module'], ''), ('stdlib', 'v1.27.3'), ('golang.org/x/net', 'v0.62.0'),
                 ('github.com/go-jose/go-jose/v4', 'v4.1.6')]]}]}
        proof = {'image_id': self.candidate['image_id'], 'resolved_digest': self.candidate['image_id'],
                 'identity_authority': 'local-configuration-and-archive'}
        with tempfile.TemporaryDirectory() as temporary:
            contract = Path(temporary) / 'candidate.json'
            contract.write_text(json.dumps(self.candidate))
            with patch.object(EVIDENCE, 'CONTRACT', contract), patch.object(EVIDENCE, 'validate_build',
                    return_value=(metadata, {}, {})):
                EVIDENCE.validate(self.image, proof, report, Path(temporary), 'fixture-head')
                report['Results'][0]['Packages'][1]['Version'] = 'v1.27.2'
                with self.assertRaisesRegex(ValueError, 'package readback'):
                    EVIDENCE.validate(self.image, proof, report, Path(temporary), 'fixture-head')

    def test_source_patch_and_go_manifests_cannot_drift(self):
        for key in ('upstream_source_sha', 'upstream_archive_sha256', 'patch_sha256', 'go_mod_sha256', 'go_sum_sha256'):
            with self.subTest(key=key):
                old = self.candidate[key]
                self.candidate[key] = 'changed'
                with self.assertRaisesRegex(ValueError, 'source'):
                    self.validate()
                self.candidate[key] = old

    def test_unexpected_builder_or_compiler_bytes_are_rejected(self):
        for key in ('builder', 'builder_image_id', 'builder_manifest', 'builder_binary_sha256', 'sdk_sha256'):
            with self.subTest(key=key):
                old = self.candidate[key]
                self.candidate[key] = 'changed'
                with self.assertRaisesRegex(ValueError, 'compiler'):
                    self.validate()
                self.candidate[key] = old

    def test_nonreproducible_executable_is_rejected(self):
        self.candidate['repeat_sha256'] = 'c' * 64
        with self.assertRaisesRegex(ValueError, 'nonreproducible'):
            self.validate()

    def test_same_compiler_cache_cannot_be_reported_as_independent_builds(self):
        self.candidate['independent_cold_caches'] = False
        with self.assertRaisesRegex(ValueError, 'settings'):
            self.validate()

    def test_legacy_docker_module_in_actual_binary_cannot_be_waived(self):
        self.candidate['buildinfo']['dependencies']['github.com/docker/docker'] = {'version': 'v28.5.2+incompatible', 'sum': 'h1:fixture'}
        with self.assertRaisesRegex(ValueError, 'Legacy Docker'):
            self.validate()

    def test_scanner_advisory_exceptions_are_rejected(self):
        self.candidate['exceptions'] = ['CVE-2026-78669']
        with self.assertRaisesRegex(ValueError, 'exception'):
            self.validate()

    def test_scanner_applicability_waiver_is_rejected(self):
        self.candidate['applicability'] = ['GO-2026-5932']
        with self.assertRaisesRegex(ValueError, 'waiver'):
            self.validate()


class IndependentTrivyAnalysisTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.metadata = {'path': 'github.com/aquasecurity/trivy/cmd/trivy',
                         'main_module': 'github.com/aquasecurity/trivy',
                         'dependencies': {'golang.org/x/net': {'version': 'v0.60.0'}}}
        self.packages = [{'ImportPath': 'net/http'}, {'ImportPath': 'golang.org/x/net/http2',
                         'Module': {'Path': 'golang.org/x/net'}}]
        functions = sorted(['net/http.F' + str(i) for i in range(10001)])
        self.symbols = {'binary_sha256': 'b' * 64, 'reader_go_version': 'go1.27.3',
                        'function_count': len(functions), 'functions': functions}
        self.candidate = {'binary_sha256': 'b' * 64, 'observed_module_notices': ['GO-FIXTURE'],
                          'binary_functions': {'count': len(functions), 'names_sha256':
                              EVIDENCE.sha(json.dumps(functions, separators=(',', ':')).encode())}}
        self.streams = {}
        self.receipt = {'analysis': {}}
        self.acquired_at = NOW
        clock_patch = patch.object(EVIDENCE.BUILD.RYUK.ADVISORY, 'datetime', wraps=datetime)
        clock = clock_patch.start()
        self.addCleanup(clock_patch.stop)
        clock.now.return_value = NOW
        for mode in ('source', 'binary'):
            self.streams[mode] = [
                {'config': {'scan_mode': mode, 'scan_level': 'symbol', 'scanner_name': 'govulncheck',
                            'scanner_version': BUILD.TOOL, 'db': 'https://vuln.go.dev',
                            'db_last_modified': MODIFIED}},
                {'SBOM': {'go_version': 'go1.27.3', 'roots': [self.metadata['path' if mode == 'source' else 'main_module']],
                          'modules': [{'path': 'golang.org/x/net', 'version': 'v0.60.0'}]}},
                {'osv': {'id': 'GO-FIXTURE', 'affected': [{'package': {'name': 'golang.org/x/crypto'},
                         'ecosystem_specific': {'imports': [{'path': 'golang.org/x/crypto/openpgp'}]}}]}},
                {'finding': {'osv': 'GO-FIXTURE', 'trace': [{'module': 'golang.org/x/crypto'}]}}]

    def validate(self):
        (self.directory / 'binary-symbols.json').write_text(json.dumps(self.symbols))
        for mode, stream in self.streams.items():
            data = ''.join(json.dumps(m) + '\n' for m in stream).encode()
            (self.directory / (mode + '-govulncheck.json')).write_bytes(data)
            status = self.receipt['analysis'].get(mode, {}).get('status', 0)
            self.receipt['analysis'][mode] = fixture(self.directory, mode, now=self.acquired_at)
            self.receipt['analysis'][mode]['status'] = status
        return EVIDENCE.advisory_analysis(self.candidate, self.receipt, self.directory, self.metadata, self.packages)

    def test_module_notice_remains_visible_only_when_all_affected_code_is_absent(self):
        result = self.validate()
        self.assertEqual(['GO-FIXTURE'], result['source']['module_notices'])
        self.assertEqual(0, result['binary']['callable_findings'])

    def test_advisory_service_error_cannot_be_zero_findings(self):
        for status in (2, 137):
            self.receipt['analysis']['source'] = {'status': status, 'diagnostics_sha256': '0' * 64}
            with self.assertRaisesRegex(ValueError, 'failed independent'):
                self.validate()

    def test_self_scan_cannot_substitute_for_independent_authority(self):
        self.streams['binary'][0]['config']['scanner_name'] = 'trivy'
        with self.assertRaisesRegex(ValueError, 'authority'):
            self.validate()

    def test_stale_acquisition_cannot_be_clean(self):
        self.acquired_at = NOW - timedelta(days=3)
        with self.assertRaisesRegex(ValueError, 'Stale'):
            self.validate()

    def test_future_advisory_metadata_is_rejected(self):
        self.streams['source'][0]['config']['db_last_modified'] = '2099-01-01T00:00:00Z'
        with self.assertRaisesRegex(ValueError, 'Future/contradictory'):
            self.validate()

    def test_complete_compiled_module_inventory_is_required(self):
        self.streams['binary'][1]['SBOM']['modules'] = []
        with self.assertRaisesRegex(ValueError, 'omitted compiled'):
            self.validate()

    def test_incomplete_function_readback_is_rejected(self):
        self.symbols['functions'].pop()
        self.symbols['function_count'] -= 1
        with self.assertRaisesRegex(ValueError, 'function readback|function census'):
            self.validate()

    def test_actual_callable_finding_is_rejected(self):
        self.streams['binary'][-1]['finding']['trace'][0].update(package='golang.org/x/crypto/openpgp', function='ReadMessage')
        with self.assertRaisesRegex(ValueError, 'callable'):
            self.validate()

    def test_affected_compiled_package_cannot_borrow_module_only_notice(self):
        self.packages.append({'ImportPath': 'golang.org/x/crypto/openpgp', 'Module': {'Path': 'golang.org/x/crypto'}})
        with self.assertRaisesRegex(ValueError, 'compiled scanner'):
            self.validate()

    def test_new_advisory_requires_review(self):
        self.streams['source'][-1]['finding']['osv'] = 'GO-UNREVIEWED'
        with self.assertRaisesRegex(ValueError, 'Unexpected independent'):
            self.validate()

    def test_missing_advisory_body_is_rejected(self):
        del self.streams['source'][2]
        with self.assertRaisesRegex(ValueError, 'authoritative advisory body'):
            self.validate()

    def test_analyzer_error_message_is_rejected(self):
        self.streams['binary'].append({'error': 'database unavailable'})
        with self.assertRaisesRegex(ValueError, 'service failure'):
            self.validate()

    def test_missing_upstream_provenance_fails_closed(self):
        with self.assertRaises(FileNotFoundError):
            EVIDENCE.validate_upstream(self.directory)

    def test_replaced_module_build_information_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Unsupported/replaced'):
            EVIDENCE.buildinfo('fixture: go1.27.3\n\t=>\tlocal/module\n')

    def test_truncated_stream_is_rejected(self):
        with self.assertRaises(json.JSONDecodeError):
            EVIDENCE.messages(b'{"config":')


class TrivyCiTrustTest(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location('auxiliary', ROOT / 'scripts/validate-auxiliary-containers.py')
        self.policy = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.policy)
        self.contract = json.loads(self.policy.CONTRACT.read_text())

    def test_scanner_cannot_be_removed_from_governance(self):
        self.contract['images']['scanner']['governed'] = False
        with self.assertRaisesRegex(ValueError, 'governance'):
            self.policy.inventory(self.contract)

    def test_scanner_threshold_cannot_be_lowered(self):
        self.contract['images']['scanner']['threshold'] = ['CRITICAL']
        with self.assertRaisesRegex(ValueError, 'threshold'):
            self.policy.inventory(self.contract)

    def test_missing_reproducible_build_receipt_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaises(FileNotFoundError):
                EVIDENCE.validate_build(json.loads(EVIDENCE.CONTRACT.read_text()), Path(temporary), 'current')

    def test_upstream_attestation_cannot_be_claimed_for_rebuilt_executable(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'upstream.json'
            path.write_text(json.dumps({'verified': True, 'image': self.contract['images']['scanner']['reference']}))
            with self.assertRaisesRegex(ValueError, 'upstream source'):
                EVIDENCE.validate_upstream(Path(temporary))


if __name__ == '__main__':
    unittest.main()
