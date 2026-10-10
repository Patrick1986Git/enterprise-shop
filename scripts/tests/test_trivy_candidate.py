import importlib.util
import io
import json
import tarfile
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).parents[2]
SPEC = importlib.util.spec_from_file_location('trivy_builder', ROOT / 'scripts/build-trivy-candidate.py')
BUILD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUILD)


class TrivyBuildTest(unittest.TestCase):
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
            self.assertEqual({'usr/local/bin/trivy', 'etc/ssl/certs/ca-certificates.crt', 'contrib/test.tpl'}, set(census))
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

    def test_collection_does_not_replace_government_scanner_authority(self):
        contract = json.loads((ROOT / '.github/security/auxiliary-container-scope.json').read_text())
        self.assertEqual('upstream-github-attestation-and-executable-byte-match', contract['scanner_independent_integrity'])
        builder = contract['images']['scanner-builder']
        self.assertEqual(BUILD.BUILDER, builder['reference'])
        self.assertEqual(BUILD.BUILDER_BINARY, builder['identity']['sha256'])
        self.assertTrue(builder['governed'])
        self.assertEqual(['HIGH', 'CRITICAL'], builder['threshold'])


if __name__ == '__main__':
    unittest.main()
