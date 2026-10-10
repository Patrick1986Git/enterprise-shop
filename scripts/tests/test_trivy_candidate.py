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
SPEC = importlib.util.spec_from_file_location('trivy_compatibility', ROOT / 'scripts/verify-trivy-candidate-compatibility.py')
COMPAT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(COMPAT)


class TrivyBuildTest(unittest.TestCase):
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
