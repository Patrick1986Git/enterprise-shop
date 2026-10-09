import importlib.util
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('cleanup', Path(__file__).parents[1] / 'check-ryuk-cleanup.py')
CLEANUP = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CLEANUP)


class RyukCleanupTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.reference = 'local/enterprise-shop-ryuk:sha256-' + 'b' * 64
        resources = self.root / 'src/test/resources'
        resources.mkdir(parents=True)
        (resources / 'testcontainers.properties').write_text('ryuk.container.image=' + self.reference + '\n')
        candidate = self.root / '.github/security/ryuk'
        candidate.mkdir(parents=True)
        import json
        (candidate / 'candidate.json').write_text(json.dumps({'image_reference': self.reference, 'image_id': 'sha256:' + 'b' * 64}))
        target = self.root / 'target/ryuk-compatibility'
        target.mkdir(parents=True)
        self.properties = target / 'execution.properties'
        self.properties.write_text('reference=' + self.reference.replace(':', '\\:')
            + '\nimage_id=sha256\\:' + 'b' * 64 + '\nryuk_container_id=' + 'c' * 64
            + '\ncleanup_container_ids=' + 'd' * 64 + '\nsocket_verified=true\n')

    def test_observed_resources_must_all_be_removed(self):
        missing = subprocess.CompletedProcess([], 1, '', 'Error: No such container')
        with patch.object(CLEANUP, 'ROOT', self.root), patch.object(CLEANUP.subprocess, 'run', return_value=missing) as run:
            CLEANUP.verify()
            self.assertEqual(2, run.call_count)
        self.assertTrue((self.root / '.tmp/ryuk-compatibility/cleanup.json').is_file())

    def test_daemon_failure_cannot_be_interpreted_as_cleanup(self):
        denied = subprocess.CompletedProcess([], 1, '', 'Cannot connect to Docker daemon')
        with patch.object(CLEANUP, 'ROOT', self.root), patch.object(CLEANUP.subprocess, 'run', return_value=denied):
            with self.assertRaisesRegex(ValueError, 'inspection failed'): CLEANUP.verify()

    def test_resources_remaining_after_deadline_block(self):
        present = subprocess.CompletedProcess([], 0, '[{}]', '')
        with patch.object(CLEANUP, 'ROOT', self.root), patch.object(CLEANUP.subprocess, 'run', return_value=present), \
                patch.object(CLEANUP.time, 'monotonic', side_effect=[0, 46]):
            with self.assertRaisesRegex(ValueError, 'resources remain'): CLEANUP.verify()

    def test_changed_execution_reference_blocks(self):
        self.properties.write_text(self.properties.read_text().replace('local/enterprise-shop-ryuk', 'local/unreviewed-ryuk'))
        with patch.object(CLEANUP, 'ROOT', self.root):
            with self.assertRaisesRegex(ValueError, 'execution reference'): CLEANUP.verify()

    def test_changed_image_configuration_blocks(self):
        self.properties.write_text(self.properties.read_text().replace('image_id=sha256\\:' + 'b' * 64,
                                                                      'image_id=sha256\\:' + 'a' * 64))
        with patch.object(CLEANUP, 'ROOT', self.root):
            with self.assertRaisesRegex(ValueError, 'configuration'): CLEANUP.verify()

    def test_missing_socket_evidence_blocks(self):
        self.properties.write_text(self.properties.read_text().replace('socket_verified=true', 'socket_verified=false'))
        with patch.object(CLEANUP, 'ROOT', self.root):
            with self.assertRaisesRegex(ValueError, 'socket'): CLEANUP.verify()


if __name__ == '__main__':
    unittest.main()
