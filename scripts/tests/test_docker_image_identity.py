import copy
import gzip
import hashlib
import importlib.util
import io
import json
import tarfile
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


ROOT = Path(__file__).parents[2]
SPEC = importlib.util.spec_from_file_location('image_identity', ROOT / 'scripts/auxiliary_binary_evidence.py')
IDENTITY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(IDENTITY)
FIXTURE = ROOT / 'scripts/tests/fixtures/docker-identity/docker29-image.tar.gz'
CONFIG_ID = 'sha256:5069b87377ce91b47890d84793e8f68efe42e927f0b423fe958b7c428ef30379'
MANIFEST_ID = 'sha256:22a761becced308c3ef0e30f3a73ca236012b1f02a9109ddb2d2f418dd91a8ff'


def digest(data):
    return 'sha256:' + hashlib.sha256(data).hexdigest()


def encoded(value):
    return json.dumps(value, separators=(',', ':')).encode()


def packed(files):
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode='w') as archive:
        for name, data in files.items():
            member = tarfile.TarInfo(name)
            member.size = len(data)
            archive.addfile(member, io.BytesIO(data))
    return output.getvalue()


class DockerImageIdentityTest(unittest.TestCase):
    def setUp(self):
        with tarfile.open(FIXTURE) as archive:
            self.files = {member.name: archive.extractfile(member).read()
                          for member in archive if member.isfile()}
        self.config = self.files['blobs/sha256/' + CONFIG_ID.split(':')[1]]
        self.manifest = json.loads(self.files['blobs/sha256/' + MANIFEST_ID.split(':')[1]])

    def verify(self, image_id=MANIFEST_ID, files=None):
        with tarfile.open(fileobj=io.BytesIO(packed(self.files if files is None else files))) as archive:
            return IDENTITY.saved_configuration(archive, image_id)

    def replace_manifest(self, manifest):
        raw = encoded(manifest)
        image_id = digest(raw)
        self.files['blobs/sha256/' + image_id.split(':')[1]] = raw
        index = json.loads(self.files['index.json'])
        index['manifests'][0].update(digest=image_id, size=len(raw))
        self.files['index.json'] = encoded(index)
        return image_id

    def test_actual_docker29_export_preserves_exact_legacy_configuration(self):
        self.assertEqual(CONFIG_ID, digest(self.config))
        self.assertEqual(self.config, self.verify())
        self.assertEqual(self.config, self.verify(CONFIG_ID))
        # This is the old Ryuk/PostgreSQL assertion, reproduced on actual saved bytes.
        self.assertNotEqual(MANIFEST_ID, digest(self.config))

    def test_index_identity_must_bind_one_exact_platform_manifest(self):
        descriptor = json.loads(self.files['index.json'])['manifests'][0]
        descriptor['platform'] = {'os': 'linux', 'architecture': 'amd64'}
        index = encoded({'schemaVersion': 2, 'mediaType': 'application/vnd.oci.image.index.v1+json',
                         'manifests': [descriptor]})
        image_id = digest(index)
        self.files['blobs/sha256/' + image_id.split(':')[1]] = index
        self.files['index.json'] = encoded({'schemaVersion': 2, 'manifests': [
            {'digest': image_id, 'size': len(index), 'mediaType': 'application/vnd.oci.image.index.v1+json'}]})
        self.assertEqual(self.config, self.verify(image_id))
        for platform in ({'os': 'linux', 'architecture': 'arm64'},
                         {'os': 'windows', 'architecture': 'amd64'}):
            with self.subTest(platform=platform):
                altered = json.loads(index)
                altered['manifests'][0]['platform'] = platform
                self.reject_index(altered)
        altered = json.loads(index)
        altered['manifests'] *= 2
        self.reject_index(altered)

    def reject_index(self, document):
        files = copy.deepcopy(self.files)
        raw = encoded(document)
        image_id = digest(raw)
        files['blobs/sha256/' + image_id.split(':')[1]] = raw
        root = json.loads(files['index.json'])
        root['manifests'][0].update(digest=image_id, size=len(raw))
        files['index.json'] = encoded(root)
        with self.assertRaises(ValueError):
            self.verify(image_id, files)

    def test_changed_config_bytes_are_never_normalized_or_reserialized(self):
        for raw in (self.config + b'\n', self.config.replace(b'amd64', b'arm64'),
                    self.config.replace(b'/bin/ryuk', b'/bin/evil')):
            with self.subTest(raw=raw):
                files = copy.deepcopy(self.files)
                files['blobs/sha256/' + CONFIG_ID.split(':')[1]] = raw
                with self.assertRaises(ValueError):
                    self.verify(files=files)

    def test_descriptor_digest_size_type_and_schema_are_verified(self):
        for change in (lambda m: m['config'].update(digest='sha256:' + '0' * 64),
                       lambda m: m['config'].update(size=m['config']['size'] + 1),
                       lambda m: m['config'].update(mediaType='application/unknown'),
                       lambda m: m.update(schemaVersion=1),
                       lambda m: m.update(mediaType='application/unknown'),
                       lambda m: m.update(layers=[])):
            with self.subTest(change=change):
                manifest = copy.deepcopy(self.manifest)
                change(manifest)
                image_id = self.replace_manifest(manifest)
                with self.assertRaises(ValueError):
                    self.verify(image_id)

    def test_changed_layer_missing_blob_and_stale_image_id_are_rejected(self):
        layer_name = 'blobs/sha256/' + self.manifest['layers'][0]['digest'].split(':')[1]
        for name in (layer_name, 'blobs/sha256/' + MANIFEST_ID.split(':')[1]):
            for replacement in (None, b'corrupted'):
                with self.subTest(name=name, replacement=replacement):
                    files = copy.deepcopy(self.files)
                    if replacement is None:
                        del files[name]
                    else:
                        files[name] = replacement
                    with self.assertRaises(ValueError):
                        self.verify(files=files)
        with self.assertRaises(ValueError):
            self.verify('sha256:' + '0' * 64)

    def test_inconsistent_docker_and_oci_layer_orders_are_rejected(self):
        files = copy.deepcopy(self.files)
        entry = json.loads(files['manifest.json'])
        entry[0]['Layers'] = [entry[0]['Config']]
        files['manifest.json'] = encoded(entry)
        with self.assertRaises(ValueError):
            self.verify(files=files)

    def test_duplicate_fields_and_ambiguous_exports_are_rejected(self):
        for raw in (b'[{"Config":"a","Config":"b","Layers":[]}]',
                    encoded(json.loads(self.files['manifest.json']) * 2)):
            files = copy.deepcopy(self.files)
            files['manifest.json'] = raw
            with self.assertRaises(ValueError):
                self.verify(files=files)

    def test_duplicate_or_linked_blob_members_are_rejected(self):
        name = 'blobs/sha256/' + CONFIG_ID.split(':')[1]
        for kind in (tarfile.REGTYPE, tarfile.SYMTYPE):
            output = io.BytesIO(packed(self.files))
            with tarfile.open(fileobj=output, mode='a') as archive:
                member = tarfile.TarInfo(name)
                member.type = kind
                member.linkname = 'elsewhere' if kind == tarfile.SYMTYPE else ''
                archive.addfile(member)
            with tarfile.open(fileobj=io.BytesIO(output.getvalue())) as archive:
                with self.assertRaises(ValueError):
                    IDENTITY.saved_configuration(archive, MANIFEST_ID)

    def test_platform_filtered_export_cannot_substitute_a_rewritten_index(self):
        files = copy.deepcopy(self.files)
        files['index.json'] = encoded({'schemaVersion': 2, 'manifests': []})
        with self.assertRaises(ValueError):
            self.verify(files=files)

    def test_inspection_binds_export_to_native_id_and_reviewed_config(self):
        observed = [{'Id': MANIFEST_ID, 'Os': 'linux', 'Architecture': 'amd64', 'Size': 65536}]
        def save(command, check, **kwargs):
            self.assertEqual(MANIFEST_ID, command[-1])
            Path(command[4]).write_bytes(gzip.decompress(FIXTURE.read_bytes()))
        with patch.object(IDENTITY.subprocess, 'check_output', return_value=encoded(observed)), \
                patch.object(IDENTITY.subprocess, 'run', side_effect=save):
            self.assertEqual((observed[0], CONFIG_ID), IDENTITY.inspect_configuration('fixture:tag', CONFIG_ID))
            with self.assertRaisesRegex(ValueError, 'configuration differs'):
                IDENTITY.inspect_configuration('fixture:tag', 'sha256:' + '0' * 64)

    def test_retained_original_metadata_supports_offline_configuration_binding(self):
        with tarfile.open(fileobj=io.BytesIO(packed(self.files))) as archive:
            _, config, _, metadata = IDENTITY.configuration_metadata(archive, MANIFEST_ID)
        self.assertEqual(self.config, config)
        with tempfile.TemporaryDirectory() as temporary:
            proof = Path(temporary) / 'identity.tar'
            proof.write_bytes(packed(metadata))
            self.assertEqual(CONFIG_ID, IDENTITY.configuration_evidence(proof, MANIFEST_ID))
            with self.assertRaises(ValueError):
                IDENTITY.configuration_evidence(proof, 'sha256:' + '0' * 64)
            metadata['blobs/sha256/' + CONFIG_ID.split(':')[1]] += b'\n'
            proof.write_bytes(packed(metadata))
            with self.assertRaises(ValueError):
                IDENTITY.configuration_evidence(proof, MANIFEST_ID)

    def test_collection_retains_metadata_only_after_full_layer_verification(self):
        observed = [{'Id': MANIFEST_ID, 'Os': 'linux', 'Architecture': 'amd64', 'Size': 65536}]
        def save(command, check, **kwargs):
            Path(command[4]).write_bytes(gzip.decompress(FIXTURE.read_bytes()))
        with tempfile.TemporaryDirectory() as temporary, \
                patch.object(IDENTITY.subprocess, 'check_output', return_value=encoded(observed)), \
                patch.object(IDENTITY.subprocess, 'run', side_effect=save):
            proof = Path(temporary) / 'identity.tar'
            IDENTITY.inspect_configuration('fixture:tag', CONFIG_ID, proof)
            self.assertEqual(CONFIG_ID, IDENTITY.configuration_evidence(proof, MANIFEST_ID))
            with tarfile.open(proof) as archive:
                layer = 'blobs/sha256/' + self.manifest['layers'][0]['digest'].split(':')[1]
                self.assertNotIn(layer, archive.getnames())

    def test_nonfinite_json_and_noninteger_oci_schema_are_rejected(self):
        for value in ('NaN', 'Infinity', '-Infinity', '1e9999', '-1e9999'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                IDENTITY.image_json(('{"value":' + value + '}').encode())
        manifest = copy.deepcopy(self.manifest)
        manifest['schemaVersion'] = 2.0
        with self.assertRaises(ValueError):
            self.verify(self.replace_manifest(manifest))

    def test_metadata_only_evidence_rejects_invalid_layer_descriptors(self):
        for field, value in (('size', -1), ('size', True), ('digest', 'unbound'),
                             ('mediaType', 'application/unknown')):
            with self.subTest(field=field, value=value):
                manifest = copy.deepcopy(self.manifest)
                manifest['layers'][0][field] = value
                native = self.replace_manifest(manifest)
                with tarfile.open(fileobj=io.BytesIO(packed(self.files))) as archive:
                    with self.assertRaises(ValueError):
                        IDENTITY.configuration_metadata(archive, native)

    def test_metadata_read_has_a_size_bound(self):
        with patch.object(IDENTITY, 'MAX_METADATA_BYTES', 32, create=True):
            with self.assertRaises(ValueError):
                self.verify()

    def test_layer_reads_are_bounded_without_changing_identity(self):
        layer_name = 'blobs/sha256/' + self.manifest['layers'][0]['digest'].split(':')[1]
        class BoundedLayer(io.BytesIO):
            def read(self, size=-1):
                if size < 0 or size > 1024 * 1024:
                    raise AssertionError('Layer read must have a bounded buffer')
                return super().read(size)
        with tarfile.open(fileobj=io.BytesIO(packed(self.files))) as archive:
            original = archive.extractfile
            def extract(member):
                name = member.name if isinstance(member, tarfile.TarInfo) else member
                return BoundedLayer(self.files[name]) if name == layer_name else original(member)
            with patch.object(archive, 'extractfile', side_effect=extract):
                self.assertEqual(self.config, IDENTITY.saved_configuration(archive, MANIFEST_ID))

    def test_failed_expected_identity_cannot_leave_retained_proof(self):
        observed = [{'Id': MANIFEST_ID, 'Os': 'linux', 'Architecture': 'amd64', 'Size': 65536}]
        def save(command, check, **kwargs):
            Path(command[4]).write_bytes(gzip.decompress(FIXTURE.read_bytes()))
        with tempfile.TemporaryDirectory() as temporary, \
                patch.object(IDENTITY.subprocess, 'check_output', return_value=encoded(observed)), \
                patch.object(IDENTITY.subprocess, 'run', side_effect=save):
            proof = Path(temporary) / 'identity.tar'
            proof.write_bytes(b'stale proof')
            with self.assertRaises(ValueError):
                IDENTITY.inspect_configuration('fixture:tag', 'sha256:' + '0' * 64, proof)
            self.assertFalse(proof.exists())

    def test_compressed_layer_expansion_has_a_size_bound(self):
        data = b'expanded layer' * 1024
        compressed = gzip.compress(data, mtime=0)
        config = encoded({'os': 'linux', 'architecture': 'amd64',
                          'rootfs': {'type': 'layers', 'diff_ids': [digest(data)]}})
        files = {'config.json': config, 'layer.tar.gz': compressed,
                 'manifest.json': encoded([{'Config': 'config.json', 'Layers': ['layer.tar.gz']}])}
        with patch.object(IDENTITY, 'MAX_LAYER_BYTES', 1024):
            with self.assertRaisesRegex(ValueError, 'Oversized image layer'):
                self.verify(digest(config), files)

    def test_insufficient_storage_cannot_start_export_or_reuse_proof(self):
        from types import SimpleNamespace
        observed = [{'Id': MANIFEST_ID, 'Os': 'linux', 'Architecture': 'amd64', 'Size': 65536}]
        with tempfile.TemporaryDirectory() as temporary, \
                patch.object(IDENTITY.subprocess, 'check_output', return_value=encoded(observed)), \
                patch.object(IDENTITY.subprocess, 'run') as save, \
                patch.object(IDENTITY.shutil, 'disk_usage', return_value=SimpleNamespace(free=0)):
            proof = Path(temporary) / 'identity.tar'
            proof.write_bytes(b'stale proof')
            with self.assertRaisesRegex(ValueError, 'temporary image storage'):
                IDENTITY.inspect_configuration('fixture:tag', CONFIG_ID, proof)
            save.assert_not_called()
            self.assertFalse(proof.exists())


if __name__ == '__main__':
    unittest.main()
