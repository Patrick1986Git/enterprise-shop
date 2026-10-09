import importlib.util
import io
import json
import tarfile
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('candidate', Path(__file__).parents[1] / 'build-ryuk-candidate.py')
CANDIDATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CANDIDATE)


class CandidatePackagingTest(unittest.TestCase):
    def test_repeated_packaging_has_identical_archive_and_content_address(self):
        first = CANDIDATE.image_archive(b'reviewed binary', b'reviewed roots')
        self.assertEqual(first, CANDIDATE.image_archive(b'reviewed binary', b'reviewed roots'))
        reference, image_id, config, layer, archive = first
        self.assertEqual('sha256:' + CANDIDATE.sha(config), image_id)
        self.assertTrue(reference.endswith(CANDIDATE.sha(config)))
        with tarfile.open(fileobj=io.BytesIO(layer)) as filesystem:
            self.assertEqual(['bin/ryuk', 'etc/ssl/certs/ca-certificates.crt'], filesystem.getnames())
            for member in filesystem:
                self.assertEqual((0, 0, 0), (member.uid, member.gid, member.mtime))
        self.assertEqual(['sha256:' + CANDIDATE.sha(layer)], json.loads(config)['rootfs']['diff_ids'])

    def test_binary_or_ca_drift_changes_both_reference_and_image_configuration(self):
        original = CANDIDATE.image_archive(b'binary', b'ca')
        for binary, ca in [(b'changed', b'ca'), (b'binary', b'changed')]:
            changed = CANDIDATE.image_archive(binary, ca)
            self.assertNotEqual(original[0], changed[0])
            self.assertNotEqual(original[1], changed[1])

    def test_reuse_revalidates_complete_build_before_accepting_loaded_configuration(self):
        reviewed = json.loads((CANDIDATE.ROOT / '.github/security/ryuk/candidate.json').read_text())
        validation = Mock(return_value=({}, {'verified': True}))
        evidence = SimpleNamespace(validate_build=validation)
        with patch.dict('sys.modules', {'ryuk_candidate_evidence': evidence}), \
                patch.object(CANDIDATE.subprocess, 'check_output', side_effect=['current head', json.dumps([{'Id': reviewed['image_id']}])]):
            result = CANDIDATE.reuse(Path('/existing-proof'), analyze=False)
        self.assertEqual({'verified': True}, result)
        validation.assert_called_once_with(reviewed, Path('/existing-proof'), 'current head', False)

    def test_reuse_rejects_wrong_loaded_candidate_configuration(self):
        evidence = SimpleNamespace(validate_build=Mock(return_value=({}, {})))
        with patch.dict('sys.modules', {'ryuk_candidate_evidence': evidence}), \
                patch.object(CANDIDATE.subprocess, 'check_output', side_effect=['current head', json.dumps([{'Id': 'sha256:' + '0' * 64}])]):
            with self.assertRaisesRegex(ValueError, 'configuration drift'):
                CANDIDATE.reuse(Path('/existing-proof'), analyze=False)


if __name__ == '__main__':
    unittest.main()
