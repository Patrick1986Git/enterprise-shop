import gzip
import hashlib
import importlib.util
import io
import json
import re
import subprocess
import tarfile
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('go_source_build', ROOT / 'scripts/build-trivy-candidate.py')
BUILD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUILD)
SPEC = importlib.util.spec_from_file_location('go_source_policy', ROOT / 'scripts/trivy_candidate_evidence.py')
POLICY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(POLICY)
FIXTURE = ROOT / 'scripts/tests/fixtures/go-remediation-source/upstream-locks.tar.gz'


class GoRemediationSourceTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        raw = FIXTURE.read_bytes()
        self.assertEqual('73188cfa9d9442a303b78e5e422dbb591ba06cb5e36508b0b064bc5637908f7a',
                         hashlib.sha256(raw).hexdigest())
        with tarfile.open(fileobj=io.BytesIO(gzip.decompress(raw))) as archive:
            self.files = {m.name: archive.extractfile(m).read() for m in archive}
        self.assertEqual({'go.mod', 'go.sum', 'reviewed-x-net.patch', 'compress-component.patch'}, set(self.files))
        self.assertEqual('6ce5f40dce24471fb5525afd9562c356db5eb8106081a12df28e3f7c432bf79f',
                         BUILD.sha(self.files['reviewed-x-net.patch']))
        self.assertEqual('d4598dc8afd3537290bde8f4b4687eaa3ec4c9b1d04e5be0ac9d9eb97a739d8a',
                         BUILD.sha(self.files['compress-component.patch']))
        data = io.BytesIO()
        with tarfile.open(fileobj=data, mode='w:gz') as archive:
            for name in ('go.mod', 'go.sum'):
                member = tarfile.TarInfo('trivy-' + BUILD.SOURCE + '/' + name)
                member.size = len(self.files[name])
                archive.addfile(member, io.BytesIO(self.files[name]))
        # Only the two-lock-file wrapper is controlled. Original lock bytes,
        # the maintained composed patch and expected resulting locks stay real.
        self.archive = data.getvalue()

    def apply(self, directory, payload):
        path = self.root / (directory.name + '.patch')
        path.write_bytes(payload)
        subprocess.run(['git', 'apply', '--check', str(path)], cwd=directory, check=True,
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        subprocess.run(['git', 'apply', str(path)], cwd=directory, check=True,
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def test_full_patch_preserves_the_reviewed_inputs_and_selects_the_november_fixes(self):
        sequential = self.root / 'sequential'
        sequential.mkdir()
        for name in ('go.mod', 'go.sum'):
            (sequential / name).write_bytes(self.files[name])
        self.apply(sequential, self.files['reviewed-x-net.patch'])
        self.apply(sequential, self.files['compress-component.patch'])
        previous = {name: (sequential / name).read_bytes() for name in ('go.mod', 'go.sum')}
        self.assertEqual('7b4a69d30dab6484f3acce81a0d38788b0969e9d1dba770881af896107efc038',
                         BUILD.sha(previous['go.mod']))
        self.assertEqual('28238faa7954aaa7504fa0fb7e2edf8491b5b41be5d5924d8e8a22c8448aac8d',
                         BUILD.sha(previous['go.sum']))
        with patch.object(BUILD, 'ARCHIVE_SHA', BUILD.sha(self.archive)):
            BUILD.prepare(self.archive, self.root / 'composed')
        composed = self.root / 'composed'
        modules = lambda data: dict(re.findall(rb'^\s*([^\s]+)\s+(v[^\s]+)', data, re.M))
        before, after = modules(previous['go.mod']), modules((composed / 'go.mod').read_bytes())
        changed = {b'github.com/containerd/containerd/v2': b'v2.4.2'}
        changed.update({b'go.opentelemetry.io/otel' + suffix: b'v1.47.0' for suffix in
                        (b'', b'/log', b'/metric', b'/sdk', b'/sdk/log', b'/sdk/metric', b'/trace')})
        self.assertEqual(changed, {n: v for n, v in after.items() if before.get(n) != v})
        self.assertEqual(b'v1.20.2', after[b'github.com/klauspost/compress'])
        self.assertEqual(b'v0.58.0', after[b'golang.org/x/crypto'])
        self.assertEqual(b'v1.4.1', after[b'github.com/ProtonMail/go-crypto'])
        self.assertEqual(set(before) | {b'go.opentelemetry.io/otel/log', b'go.opentelemetry.io/otel/sdk/log'}, set(after))
        self.assertIn(b'\tgo.opentelemetry.io/otel/log v1.47.0 // indirect\n', (composed / 'go.mod').read_bytes())
        old_sums = set(previous['go.sum'].splitlines())
        new_sums = set((composed / 'go.sum').read_bytes().splitlines())
        self.assertTrue(old_sums <= new_sums)
        selected = dict(changed)
        selected[b'go.opentelemetry.io/otel/metric/x'] = b'v0.69.0'
        self.assertEqual({(n, v + suffix) for n, v in selected.items() for suffix in (b'', b'/go.mod')},
                         {tuple(line.split()[:2]) for line in new_sums - old_sums})

    def test_changed_original_bytes_fail_before_patch_or_source_creation(self):
        with patch.object(BUILD, 'ARCHIVE_SHA', BUILD.sha(self.archive)):
            with self.assertRaisesRegex(ValueError, 'Unreviewed upstream source archive'):
                BUILD.prepare(self.archive + b'changed bytes', self.root / 'rejected')
        self.assertFalse((self.root / 'rejected').exists())

    def test_rehashed_tampered_patch_cannot_forge_the_expected_source_locks(self):
        contents = (ROOT / '.github/security/trivy/x-net.patch').read_bytes()
        changed = contents.replace(b'h1:v5tiZvm1', b'h1:INVALID', 1)
        self.assertNotEqual(contents, changed)
        local = self.root / 'policy/.github/security/trivy'
        local.mkdir(parents=True)
        (local / 'x-net.patch').write_bytes(changed)
        with patch.object(BUILD, 'ARCHIVE_SHA', BUILD.sha(self.archive)), \
                patch.object(BUILD, 'ROOT', self.root / 'policy'), \
                patch.object(BUILD, 'PATCH_SHA', BUILD.sha(changed)):
            with self.assertRaisesRegex(ValueError, 'Patched Go module identities differ'):
                BUILD.prepare(self.archive, self.root / 'tampered')

    def test_historical_runtime_manifest_cannot_admit_the_changed_source_recipe(self):
        candidate = json.loads((ROOT / '.github/security/trivy/candidate.json').read_text())
        contract = json.loads((ROOT / '.github/security/auxiliary-container-scope.json').read_text())
        POLICY.inventory(contract['images']['scanner'], candidate)
        candidate['go_version'] = '1.27.3'
        with self.assertRaisesRegex(ValueError, 'Unreviewed scanner executable'):
            POLICY.inventory(contract['images']['scanner'], candidate)
