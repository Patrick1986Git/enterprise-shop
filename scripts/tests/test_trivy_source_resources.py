"""Replay retained resource records and exercise terminal collection failures offline."""
import copy
import json
import os
import tarfile
import tempfile
import unittest
from contextlib import ExitStack
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from test_trivy_candidate import BUILD, EVIDENCE, ROOT

RESOURCES = BUILD.RESOURCES
STAMP = BUILD.RYUK.ADVISORY.timestamp


class SwapCleanupRecoveryTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.original = self.directory / 'swap-cleanup.json'
        self.recovery = self.directory / 'swap-cleanup-recovery.json'
        self.path = '/var/tmp/enterprise-shop-trivy-42-a1-' + '1' * 32 + '.swap'
        self.identity = SimpleNamespace(st_dev=10, st_ino=20)
        self.owned_device = {'type': 'file', 'size_bytes': RESOURCES.SIZE - 4096}
        self.existing = {'/dev/other': {'type': 'partition', 'size_bytes': 4096}}
        self.devices = {**self.existing, self.path: self.owned_device}
        (self.directory / 'swap-provisioning.json').write_text(json.dumps({
            'path': self.path, 'identity': [10, 20], 'devices_before': self.existing}))
        runner = {'GITHUB_ACTIONS': 'true', 'RUNNER_ENVIRONMENT': 'github-hosted', 'RUNNER_OS': 'Linux',
                  'GITHUB_REPOSITORY': 'Patrick1986Git/enterprise-shop', 'GITHUB_JOB': 'trivy-candidate',
                  'GITHUB_RUN_ID': '42', 'GITHUB_RUN_ATTEMPT': '1'}
        patches = ExitStack()
        self.addCleanup(patches.close)
        patches.enter_context(patch.dict(os.environ, runner))
        patches.enter_context(patch.object(RESOURCES, 'memory', return_value={}))
        patches.enter_context(patch.object(RESOURCES, 'devices', side_effect=lambda: self.devices.copy()))
        patches.enter_context(patch.object(RESOURCES, 'owned', side_effect=self.owned))
        self.command = patches.enter_context(patch.object(RESOURCES, 'privileged', side_effect=self.mutate))

    def owned(self, path):
        self.assertEqual(self.path, str(path))
        return self.identity

    def mutate(self, _operation, *args, **_options):
        self.assertEqual(('--', self.path), args[1:])
        if args[0] == 'swapoff':
            del self.devices[self.path]
        else:
            self.assertEqual('rm', args[0])
            self.identity = None

    def test_recovery_removes_owned_swap_without_a_primary_receipt(self):
        result = RESOURCES.cleanup_swap(self.directory, recovery=True)
        self.assertEqual('PASS', result['status'])
        self.assertEqual(self.existing, self.devices)
        self.assertEqual('PASS', json.loads(self.recovery.read_bytes())['status'])
        self.assertFalse(self.original.exists())

    def test_recovery_preserves_the_failed_primary_receipt(self):
        for failure in (ValueError('swapoff exceeded its deadline'), KeyboardInterrupt()):
            with self.subTest(failure=type(failure).__name__):
                self.identity = SimpleNamespace(st_dev=10, st_ino=20)
                self.devices[self.path] = self.owned_device
                self.command.side_effect = failure
                with self.assertRaises(type(failure)):
                    RESOURCES.cleanup_swap(self.directory)
                original = self.original.read_bytes()
                self.assertEqual('BLOCKED', json.loads(original)['status'])
                self.assertIn(self.path, self.devices)
                self.command.side_effect = self.mutate
                result = RESOURCES.cleanup_swap(self.directory, recovery=True)
                self.assertEqual('PASS', result['status'])
                self.assertEqual(self.existing, self.devices)
                self.assertEqual(original, self.original.read_bytes())

    def test_recovery_is_read_only_after_successful_cleanup(self):
        self.assertEqual('PASS', RESOURCES.cleanup_swap(self.directory)['status'])
        original = self.original.read_bytes()
        self.command.reset_mock()
        self.assertEqual('PASS', RESOURCES.cleanup_swap(self.directory, recovery=True)['status'])
        self.command.assert_not_called()
        self.assertEqual(original, self.original.read_bytes())

    def test_recovery_refuses_a_replaced_swap_file(self):
        self.identity.st_ino += 1
        with self.assertRaisesRegex(ValueError, 'Swap file identity changed'):
            RESOURCES.cleanup_swap(self.directory, recovery=True)
        self.command.assert_not_called()
        self.assertEqual('BLOCKED', json.loads(self.recovery.read_bytes())['status'])

    def test_local_and_self_hosted_invocations_have_no_swap_mutation_authority(self):
        journal = self.directory / 'swap-provisioning.json'
        original = journal.read_bytes()
        for environment in ({}, {'GITHUB_ACTIONS': 'true', 'RUNNER_ENVIRONMENT': 'self-hosted'}):
            with self.subTest(environment=environment), patch.dict(os.environ, environment, clear=True):
                with self.assertRaisesRegex(ValueError, 'requires the disposable Trivy CI job'):
                    RESOURCES.provision_swap(self.directory, {})
                with self.assertRaisesRegex(ValueError, 'requires the disposable Trivy CI job'):
                    RESOURCES.cleanup_swap(self.directory, recovery=True)
        self.command.assert_not_called()
        self.assertEqual(original, journal.read_bytes())
        self.assertFalse(self.recovery.exists())

    def test_workflow_recovers_from_any_provisioning_journal(self):
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        action = workflow.split('name: Verify or recover Trivy host swap cleanup\n', 1)[1].split('\n      - name:', 1)[0]
        self.assertEqual("if: always() && hashFiles('.tmp/trivy-candidate/swap-provisioning.json') != ''",
                         action.splitlines()[0].strip())
        self.assertIn('timeout-minutes: 2', action)
        self.assertIn('run: python scripts/trivy_source_resources.py .tmp/trivy-candidate', action)


class RetainedAccountingTest(unittest.TestCase):
    def setUp(self):
        contract = json.loads(EVIDENCE.CONTRACT.read_text())['proof']
        with tarfile.open(ROOT / contract['retained_resources']) as archive:
            self.records = {}
            for name, digest in contract['resources'].items():
                data = archive.extractfile(name).read()
                self.assertEqual(digest, EVIDENCE.sha(data))
                self.records[name] = json.loads(data)
        binding = self.records['binding.json']
        self.assertEqual(contract['harness_commit'], binding['harness_commit'])
        self.assertEqual(contract['repository_head'], binding['candidate_commit'])

    def profile(self, directory):
        accounting, termination, acquisition = (
            copy.deepcopy(self.records[f'{directory}/{name}.json'])
            for name in ('terminal-accounting', 'source-termination', 'source-go-acquisition'))
        return accounting, termination, acquisition, copy.deepcopy(accounting['source_receipt'])

    def test_both_retained_terminal_profiles_and_lifecycle_fixtures(self):
        scopes = set()
        for directory, expected in [('first', 8810778624), ('independent-source', 8637120512)]:
            profile = RESOURCES.validate_terminal(*self.profile(directory), STAMP)
            self.assertEqual(expected, profile['terminal']['ram_peak_bytes'])
            scopes.add(tuple(profile['terminal']['device_inode']))
        self.assertEqual(2, len(scopes))
        for mode in ('late', 'oom'):
            RESOURCES.validate_fixture(self.records[f'accounting-{mode}-fixture.json'], mode)

    def test_invalid_terminal_evidence_fails_closed(self):
        for label, change in (
                ('incomplete scope cleanup', lambda p: p[0].update(cleanup=False)),
                ('premature analyzer cleanup', lambda p: p[0].update(analyzer_cleanup=False)),
                ('missing exit event', lambda p: p[1].update(events=[])),
                ('different container', lambda p: p[0].update(container_id='a' * 64)),
                ('different cgroup inode', lambda p: p[0]['terminal'].update(device_inode=[1, 2])),
                ('populated cgroup', lambda p: p[0]['terminal'].update(populated=1)),
                ('missing peak', lambda p: p[0]['terminal'].pop('ram_peak_bytes')),
                ('unavailable counter', lambda p: p[0]['terminal']['memory_events'].pop('oom_kill')),
                ('peak below observed live usage', lambda p: p[0]['terminal'].update(ram_peak_bytes=1)),
                ('owned swap', lambda p: p[0]['terminal'].update(swap_peak_bytes=1)),
                ('excess memory', lambda p: p[0]['terminal'].update(ram_peak_bytes=9 * RESOURCES.GIB + 1)),
                ('hard memory limit event', lambda p: p[0]['terminal']['memory_events'].update(max=1)),
                ('OOM event', lambda p: p[0]['terminal']['memory_events'].update(oom=1)),
                ('bad duration', lambda p: p[0].update(seconds=1)),
                ('bad host headroom', lambda p: p[0].update(host_swap_free_bytes_min=1))):
            with self.subTest(label=label):
                profile = self.profile('first')
                change(profile)
                with self.assertRaises((KeyError, ValueError)):
                    RESOURCES.validate_terminal(*profile, STAMP)

    def test_missing_source_repeat_prevents_admission(self):
        receipt = {'analysis': {'source': {}, 'binary': {}}}
        with self.assertRaisesRegex(ValueError, 'Two complete source analyses'):
            EVIDENCE.validate_source_resources(Path('.'), receipt, 'fixture')

    def test_host_fallback_under_current_contract_requires_exact_swap_cleanup(self):
        runner = self.records['runner-capabilities.json']
        provisioning = self.records['swap-provisioning.json']
        engine = runner['docker_version']
        host = {'status': 'PASS', 'cgroup': '2 systemd', 'mount': runner['cgroup_mount'],
                'cpus': len(runner['affinity']), 'docker': engine,
                'before': provisioning['before'], 'active': provisioning['after']}
        names = [*('accounting-' + mode + '-fixture.json' for mode in ('late', 'oom')),
                 'swap-provisioning.json', 'swap-cleanup.json']
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            for name in names:
                (directory / name).write_text(json.dumps(self.records[name]))
            (directory / 'resource-host.json').write_text(json.dumps(host))
            RESOURCES.validate_preflight(directory)
            cleanup = self.records['swap-cleanup.json']
            cleanup['remaining_devices'][provisioning['path']] = provisioning['devices_after'][provisioning['path']]
            (directory / 'swap-cleanup.json').write_text(json.dumps(cleanup))
            with self.assertRaisesRegex(ValueError, 'verified and cleaned'):
                RESOURCES.validate_preflight(directory)


class CgroupReadbackTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.path = Path(temporary.name) / 'scope'
        self.path.mkdir()
        for name, value in {'cgroup.events': 'populated 0\n', 'cgroup.stat': 'nr_descendants 0\n',
                            'cgroup.procs': '', 'memory.peak': '1024\n', 'memory.swap.peak': '0\n',
                            'memory.events': 'max 0\noom 0\noom_kill 0\n'}.items():
            (self.path / name).write_text(value)
        self.group = RESOURCES.Group(self.path, 'scope')
        self.addCleanup(os.close, self.group.fd)

    def test_stable_empty_scope_retains_peak(self):
        self.assertEqual(1024, self.group.empty_snapshot()['ram_peak_bytes'])

    def test_changed_counters_during_readback_fail(self):
        def snapshot():
            first = read()
            (self.path / 'memory.peak').write_text('2048\n')
            return first

        read = self.group.snapshot
        with patch.object(self.group, 'snapshot', side_effect=snapshot):
            with self.assertRaisesRegex(ValueError, 'changed during readback'):
                self.group.empty_snapshot()

    def test_missing_kernel_counter_fails(self):
        (self.path / 'memory.swap.peak').unlink()
        with self.assertRaises(FileNotFoundError):
            self.group.empty_snapshot()

    def test_replaced_scope_is_rejected_even_with_original_directory_open(self):
        self.path.rename(self.path.with_name('previous'))
        self.path.mkdir()
        with self.assertRaisesRegex(ValueError, 'cgroup identity changed'):
            self.group.empty_snapshot()

    def test_conflicting_json_fields_are_rejected(self):
        record = self.path / 'record.json'
        record.write_text('{"status":"FAIL","status":"PASS"}')
        with self.assertRaisesRegex(ValueError, 'Duplicate terminal accounting field'):
            RESOURCES.read_evidence(record)
