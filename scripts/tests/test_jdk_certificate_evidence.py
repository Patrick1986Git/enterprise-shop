import base64
import copy
import gzip
import importlib.util
import io
import json
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path
from contextlib import redirect_stdout
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('certificate_collector', ROOT / 'scripts/collect-jdk-certificate-evidence.py')
COLLECTOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(COLLECTOR)
SOURCE = '26d4c6d0a655e63a4355eab19ca3f919b06282c1'
CONTEXT_FIXTURES = ROOT / 'scripts/tests/fixtures/jdk-certificate-context'
HISTORICAL_AUTHORITY_SHA256 = '43224a83fbe39073bf39234aed54abaa4fab078e5bae6c0a477addd2f4801965'


class CertificateContextTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.inputs = self.root / 'inputs'
        self.inputs.mkdir()
        # Original two-stage OCI metadata; the source marker is a controlled
        # binding input, not an attestation about historical candidate images.
        with tarfile.open(ROOT / 'scripts/tests/fixtures/buildkit-metadata/ci1290.tar.gz') as archive:
            for member in archive:
                self.assertTrue(member.isfile())
                (self.inputs / member.name).write_bytes(archive.extractfile(member).read())
        (self.inputs / 'source-sha.txt').write_text(SOURCE)
        raw = gzip.decompress((CONTEXT_FIXTURES / 'temurin-vdr-20261124.json.gz').read_bytes())
        (self.inputs / 'temurin-vdr.json').write_bytes(raw)
        shutil.copyfile(CONTEXT_FIXTURES / 'jdk-advisory-provenance-20261124.json',
                        self.inputs / 'jdk-advisory-provenance.json')

    def test_real_context_returns_source_and_both_original_image_identities(self):
        actual = COLLECTOR.context(self.inputs, SOURCE)
        self.assertIsInstance(actual, dict)
        self.assertEqual(SOURCE, actual['source_sha'])
        self.assertEqual(COLLECTOR.AUTHORITY_SHA256, actual['authority_sha256'])
        self.assertEqual({'builder', 'runtime'}, set(actual['images']))
        expected = {
            'builder': ('sha256:5092ac198ae7a266d7ed01b31e5d861085ac2f820fd2143a1ee4da7f4bd7124e',
                        'sha256:df149d15bf89d247d666287f385251902156ddd8527cb10c780dc32fcd80139c'),
            'runtime': ('sha256:affb44ba30c0d2af18846343f98febe4a3f6574e19627554b6d2564d4bbb15b9',
                        'sha256:dee40abb5301270b2282c34658db668a88d6cff1f78c575f325dbfab16b8fe1d')}
        for stage, (native, configuration) in expected.items():
            self.assertEqual(native, actual['images'][stage]['native_id'])
            self.assertEqual(configuration, actual['images'][stage]['configuration_id'])
            self.assertEqual('linux/amd64', actual['images'][stage]['platform'])
        self.assertEqual(COLLECTOR.image_context(self.inputs, SOURCE), actual)
        self.assertEqual({'CVE-2026-114820', 'CVE-2026-91403'}, set(COLLECTOR.authority()['advisory_records']))

    def test_collection_and_cli_replay_execute_real_context(self):
        output = self.root / 'bundle'
        summary = {'trusted_certificates': 1, 'behavioral_coverage': COLLECTOR.COVERAGE.copy(),
                   'qualification': 'UNQUALIFIED'}
        calls = []

        def capture(stage, image, classes, destination, nonce):
            # Only the Docker/probe transport is simulated. Context, original
            # OCI proof, authority checks, hashing, compilation and replay run.
            calls.append(stage)
            prefix = COLLECTOR.STAGES[stage]
            shutil.copyfile(self.inputs / f'{prefix}-identity.tar', destination / f'{stage}-fresh-identity.tar')
            probe = destination / f'{stage}-probe.json'
            COLLECTOR.write(probe, {'scope': 'SYNTHETIC_TRANSPORT_FIXTURE_NO_IMAGE_MEASUREMENT'})
            cid = ('a' if stage == 'builder' else 'b') * 64
            identity = {key: image[key] for key in ('native_id', 'configuration_id', 'platform', 'bases')}
            identity.update({'container_id': cid, 'nonce': nonce, 'measurement': summary})
            COLLECTOR.write(destination / f'{stage}-container.json', {
                'stage': stage, 'container_id': cid, 'nonce': nonce, 'native_id': image['native_id'],
                'configuration_id': image['configuration_id'], 'user': image['config'].get('User'),
                'working_dir': image['config'].get('WorkingDir'), 'readonly_rootfs': True, 'network': 'none',
                'probe_sha256': COLLECTOR.sha(probe.read_bytes()), 'exit_code': 0})
            return identity

        with patch.object(COLLECTOR, 'check_source'), patch.object(COLLECTOR, 'capture', side_effect=capture), \
                patch.object(COLLECTOR, 'validate_probe', return_value=summary):
            result = COLLECTOR.collect(self.inputs, output, SOURCE)
            self.assertEqual(['builder', 'runtime'], calls)
            self.assertEqual('UNQUALIFIED', result['qualification'])
            self.assertEqual(SOURCE, result['source_sha'])
            for stage in COLLECTOR.STAGES:
                self.assertEqual(COLLECTOR.image_context(self.inputs, SOURCE)['images'][stage]['native_id'],
                                 result['images'][stage]['native_id'])
            args = ['collector', '--directory', str(self.inputs), '--output', str(output),
                    '--expected-source', SOURCE, '--replay']
            with patch.object(sys, 'argv', args), redirect_stdout(io.StringIO()) as stdout:
                self.assertEqual(0, COLLECTOR.main())
            self.assertEqual('UNQUALIFIED', json.loads(stdout.getvalue())['qualification'])

            envelope_path = output / 'envelope.json'
            envelope = COLLECTOR.read(envelope_path)
            COLLECTOR.write(envelope_path, dict(envelope, authority_sha256=HISTORICAL_AUTHORITY_SHA256))
            with patch.object(sys, 'argv', args), redirect_stdout(io.StringIO()):
                self.assertEqual(2, COLLECTOR.main())
            COLLECTOR.write(envelope_path, envelope)

            # Even a self-consistent artifact census cannot hide stale retained
            # authority from the real context() call inside bundle replay.
            provenance_path = output / 'provenance/jdk-advisory-provenance.json'
            provenance = COLLECTOR.read(provenance_path)
            provenance['tag'] = 'unreviewed-retained-release'
            COLLECTOR.write(provenance_path, provenance)
            envelope['artifacts'] = COLLECTOR.census(output)
            COLLECTOR.write(output / 'envelope.json', envelope)
            with patch.object(sys, 'argv', args), redirect_stdout(io.StringIO()):
                self.assertEqual(2, COLLECTOR.main())

    def test_collection_and_cli_replay_reject_wrong_authority_before_transport(self):
        path = self.inputs / 'jdk-advisory-provenance.json'
        provenance = COLLECTOR.read(path)
        provenance['tag'] = 'unreviewed-release'
        COLLECTOR.write(path, provenance)
        output = self.root / 'must-not-be-created'
        with patch.object(COLLECTOR, 'check_source'), patch.object(COLLECTOR, 'capture') as capture, \
                patch.object(COLLECTOR, 'compile_probe') as compile_probe:
            with self.assertRaisesRegex(ValueError, 'Stale advisory authority'):
                COLLECTOR.collect(self.inputs, output, SOURCE)
            args = ['collector', '--directory', str(self.inputs), '--output', str(output),
                    '--expected-source', SOURCE, '--replay']
            with patch.object(sys, 'argv', args), redirect_stdout(io.StringIO()):
                self.assertEqual(2, COLLECTOR.main())
            capture.assert_not_called()
            compile_probe.assert_not_called()
        self.assertFalse(output.exists())

    def test_altered_missing_or_duplicate_records_fail_real_record_hashing(self):
        records = COLLECTOR.read(self.inputs / 'temurin-vdr.json')['vulnerabilities']
        expected = COLLECTOR.authority()['advisory_records']
        COLLECTOR.validate_advisory_records(records, expected)
        for identity in expected:
            required = next(record for record in records if record['id'] == identity)
            others = [record for record in records if record['id'] != identity]
            for case, supplied in (('altered', others + [dict(required, recommendation='changed')]),
                                   ('missing', others), ('duplicate', records + [required])):
                with self.subTest(identity=identity, case=case):
                    with self.assertRaisesRegex(ValueError, 'Altered advisory record'):
                        COLLECTOR.validate_advisory_records(supplied, expected)

    def test_wrong_vdr_identity_or_checksum_fails(self):
        path = self.inputs / 'jdk-advisory-provenance.json'
        original = COLLECTOR.read(path)
        for field, value in (('repository', 'unreviewed/vendor'), ('tag', 'unreviewed-release'), ('sha256', '0' * 64),
                             ('release_id', 448589257), ('asset_id', 758945784), ('url', 'https://example.invalid'),
                             ('published_at', '2026-11-17T12:18:42Z'), ('upstream_immutable', 0)):
            modified = copy.deepcopy(original)
            modified[field] = value
            COLLECTOR.write(path, modified)
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, 'Stale advisory authority'):
                COLLECTOR.context(self.inputs, SOURCE)

    def test_modified_record_bytes_cannot_reuse_official_vdr_identity(self):
        path = self.inputs / 'temurin-vdr.json'
        document = COLLECTOR.read(path)
        next(r for r in document['vulnerabilities'] if r['id'] == 'CVE-2026-114820')['ratings'][0]['score'] = 0
        COLLECTOR.write(path, document)
        provenance_path = self.inputs / 'jdk-advisory-provenance.json'
        provenance = COLLECTOR.read(provenance_path)
        provenance['sha256'] = COLLECTOR.sha(path.read_bytes())
        COLLECTOR.write(provenance_path, provenance)
        with self.assertRaisesRegex(ValueError, 'Stale advisory authority'):
            COLLECTOR.context(self.inputs, SOURCE)

    def test_exact_november_17_authority_is_historical_only(self):
        raw = gzip.decompress((CONTEXT_FIXTURES / 'temurin-vdr-20261117.json.gz').read_bytes())
        historical = CONTEXT_FIXTURES / 'jdk-advisory-provenance.json'
        self.assertEqual(COLLECTOR.read(historical)['sha256'], COLLECTOR.sha(raw))
        (self.inputs / 'temurin-vdr.json').write_bytes(raw)
        shutil.copyfile(historical, self.inputs / 'jdk-advisory-provenance.json')
        with self.assertRaisesRegex(ValueError, 'Stale advisory authority'):
            COLLECTOR.context(self.inputs, SOURCE)

    def test_swapped_original_oci_proof_fails_real_context(self):
        shutil.copyfile(self.inputs / 'builder-identity.tar', self.inputs / 'app-identity.tar')
        with self.assertRaisesRegex(ValueError, 'Export does not contain the inspected immutable image'):
            COLLECTOR.context(self.inputs, SOURCE)

    def test_stale_source_and_malformed_image_provenance_fail_real_context(self):
        (self.inputs / 'source-sha.txt').write_text('c95cf2a904493db4fda4a89dd76ae5030cdd4486')
        with self.assertRaisesRegex(ValueError, 'source identity'):
            COLLECTOR.context(self.inputs, SOURCE)
        (self.inputs / 'source-sha.txt').write_text(SOURCE)
        COLLECTOR.write(self.inputs / 'builder-build.json', {})
        with self.assertRaises((ValueError, KeyError)):
            COLLECTOR.context(self.inputs, SOURCE)

    def test_missing_runtime_proof_fails_before_returning_context(self):
        (self.inputs / 'app-identity.tar').unlink()
        with self.assertRaises((OSError, ValueError)):
            COLLECTOR.context(self.inputs, SOURCE)

    def test_stale_base_resolution_fails_real_context(self):
        path = self.inputs / 'builder-build.json'
        document = COLLECTOR.read(path)
        changed = 0
        for node in document['buildx.build.provenance']['buildConfig']['llbDefinition']:
            source = node.get('op', {}).get('Op', {}).get('source')
            if source:
                source['attrs']['image.resolvemode'] = 'default'
                changed += 1
        self.assertGreater(changed, 0)
        COLLECTOR.write(path, document)
        with self.assertRaisesRegex(ValueError, 'freshly resolved base'):
            COLLECTOR.context(self.inputs, SOURCE)


class PublicCertificateProbeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.root = Path(cls.temporary.name)
        cls.classes = COLLECTOR.compile_probe(cls.root / 'probe')
        cls.manifest = COLLECTOR.authority()
        cls.stores = {}
        cls.snapshots = {}
        cls.g1 = next(c for c in cls.manifest['certificates'] if c['category'] == 'permitted_root')
        cls.good = cls.snapshot([cls.g1])

    @classmethod
    def snapshot(cls, records):
        key = tuple(sorted(c['sha256'] for c in records))
        if key in cls.snapshots:
            return copy.deepcopy(cls.snapshots[key])
        directory = cls.root / ('store-' + str(len(cls.snapshots)))
        directory.mkdir()
        store = directory / 'public.jks'
        for record in records:
            source = (COLLECTOR.FIXTURES / record['source_file']).read_bytes()
            pem = re.findall(rb'-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----', source, re.S)[record['pem_index']]
            certificate = directory / (record['sha256'] + '.pem')
            certificate.write_bytes(pem)
            COLLECTOR.run(['java', '-m', 'java.base/sun.security.tools.keytool.Main', '-importcert', '-noprompt',
                           '-alias', record['sha256'], '-file', str(certificate), '-keystore', str(store),
                           '-storetype', 'JKS', '-storepass', 'changeit'])
        data = COLLECTOR.invoke_probe(cls.classes, '--fixture-store', store)
        cls.stores[key] = store
        cls.snapshots[key] = data
        return copy.deepcopy(data)

    def test_permitted_g1_snapshot_remains_unqualified_without_behavioral_claims(self):
        result = COLLECTOR.validate_probe(self.good, self.classes, fixture=True)
        self.assertEqual('UNQUALIFIED', result['qualification'])
        self.assertEqual(1, result['trusted_certificates'])
        self.assertEqual({'NOT_TESTED'}, set(result['behavioral_coverage'].values()))

    def test_independent_java_parser_matches_all_reviewed_der_spki_and_purposes(self):
        data = self.snapshot(self.manifest['certificates'])
        actual = {c['sha256']: c for c in data['store']['certificates']}
        self.assertEqual(19, len(actual))
        for record in self.manifest['certificates']:
            with self.subTest(category=record['category'], fingerprint=record['sha256']):
                parsed = actual[record['sha256']]
                self.assertEqual(record['spki_sha256'], parsed['spki_sha256'])
                if record['category'] == 'compromised_code_signing_intermediate':
                    self.assertIn('1.3.6.1.5.5.7.3.3', parsed['extended_key_usage'])
                elif record['category'] == 'compromised_tls_intermediate':
                    self.assertIn('1.3.6.1.5.5.7.3.1', parsed['extended_key_usage'])

    def test_withdrawn_roots_reintroduced_into_effective_trust_are_rejected(self):
        for record in self.manifest['certificates']:
            if record['category'] == 'withdrawn_root':
                with self.subTest(fingerprint=record['sha256']), self.assertRaisesRegex(ValueError, 'Withdrawn root'):
                    COLLECTOR.validate_probe(self.snapshot([record]), self.classes, fixture=True)

    def test_tls_and_code_signing_intermediates_are_independent_negative_cases(self):
        for category in ('compromised_tls_intermediate', 'compromised_code_signing_intermediate'):
            record = next(c for c in self.manifest['certificates'] if c['category'] == category)
            with self.subTest(category=category), self.assertRaisesRegex(ValueError, 'compromised intermediate'):
                COLLECTOR.validate_probe(self.snapshot([record]), self.classes, fixture=True)

    def test_altered_store_bytes_cannot_reuse_a_valid_inventory(self):
        for change_digest in (False, True):
            data = copy.deepcopy(self.good)
            raw = bytearray(base64.b64decode(data['store']['base64']))
            raw[-1] ^= 1
            data['store']['base64'] = base64.b64encode(raw).decode()
            if change_digest:
                data['store']['sha256'] = COLLECTOR.sha(raw)
            with self.subTest(change_digest=change_digest), self.assertRaises(ValueError):
                COLLECTOR.validate_probe(data, self.classes, fixture=True)

    def test_missing_or_conflicting_der_inventory_is_rejected(self):
        for field in ('der', 'sha256', 'spki_sha256', 'subject'):
            data = copy.deepcopy(self.good)
            data['store']['certificates'][0][field] = 'altered'
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, 'DER evidence'):
                COLLECTOR.validate_probe(data, self.classes, fixture=True)
        data = copy.deepcopy(self.good)
        data['store']['certificates'] = []
        with self.assertRaises(ValueError):
            COLLECTOR.validate_probe(data, self.classes, fixture=True)

    def test_effective_trust_set_must_match_the_selected_store(self):
        data = copy.deepcopy(self.good)
        data['accepted_issuers'] = []
        with self.assertRaisesRegex(ValueError, 'effective trust inventory'):
            COLLECTOR.validate_probe(data, self.classes, fixture=True)

    def test_custom_store_and_provider_metadata_are_not_silently_accepted(self):
        for key, value in (('jssecacerts', {'present': True}), ('trust_manager_provider', 'Custom'),
                           ('ssl_context_provider', 'Custom')):
            data = copy.deepcopy(self.good)
            data[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                COLLECTOR.validate_probe(data, self.classes, fixture=True)
        data = copy.deepcopy(self.good)
        data['providers'][0]['name'] = 'Custom'
        with self.assertRaises(ValueError):
            COLLECTOR.validate_probe(data, self.classes, fixture=True)

    def test_fixture_measurement_cannot_be_promoted_to_actual_image_evidence(self):
        with self.assertRaisesRegex(ValueError, 'probe scope'):
            COLLECTOR.validate_probe(self.good, self.classes)
        data = copy.deepcopy(self.good)
        data['accepted'] = True
        with self.assertRaisesRegex(ValueError, 'probe scope'):
            COLLECTOR.validate_probe(data, self.classes, fixture=True)

    def test_feature_only_observed_version_is_rejected_in_collector_identity(self):
        # Controlled invalid identity, never an actual-image measurement.
        data = copy.deepcopy(self.good)
        data['scope'] = 'IMAGE_DEFAULT_JSSE'
        data['java'].update({'java.vendor': 'Eclipse Adoptium', 'java.version': '21',
            'java.runtime.version': '21+9-LTS', 'java.vendor.version': 'Temurin-21+9',
            'java.home': '/opt/java/openjdk', 'os.name': 'Linux', 'os.arch': 'amd64'})
        data['release']['fields'].update({'IMPLEMENTOR': 'Eclipse Adoptium', 'JAVA_VERSION': '21',
                                          'IMPLEMENTOR_VERSION': 'Temurin-21+9'})
        with self.assertRaisesRegex(ValueError, 'Wrong Temurin identity'):
            COLLECTOR.validate_probe(data, self.classes)

    def test_probe_rejects_password_override_without_echoing_value(self):
        marker = 'PRIVATE_TEST_VALUE_DO_NOT_LOG'
        result = subprocess.run(['java', '-Djavax.net.ssl.trustStorePassword=' + marker,
                                 '-cp', str(self.classes), 'CertificateEvidence', '--fixture-store',
                                 str(self.stores[(self.g1['sha256'],)])], capture_output=True, timeout=20)
        self.assertEqual(2, result.returncode)
        self.assertNotIn(marker.encode(), result.stdout + result.stderr)

    def test_probe_class_targets_java21_and_source_is_preserved(self):
        raw = (self.classes / 'CertificateEvidence.class').read_bytes()
        self.assertEqual(b'\xca\xfe\xba\xbe', raw[:4])
        self.assertEqual(65, int.from_bytes(raw[6:8], 'big'))
        self.assertEqual(COLLECTOR.PROBE.read_bytes(), (self.classes.parent / 'CertificateEvidence.java').read_bytes())

    def test_certificate_only_pkcs12_is_distinguished_from_jks(self):
        store = self.root / 'public-pkcs12.store'
        COLLECTOR.run(['java', '-m', 'java.base/sun.security.tools.keytool.Main', '-importcert', '-noprompt',
                       '-alias', 'public-g1', '-file', str(COLLECTOR.FIXTURES / 'emsignrootcag1'),
                       '-keystore', str(store), '-storetype', 'PKCS12', '-storepass', 'changeit'])
        data = COLLECTOR.invoke_probe(self.classes, '--fixture-store', store)
        self.assertEqual('PKCS12', data['store']['type'])
        self.assertEqual('UNQUALIFIED', COLLECTOR.validate_probe(data, self.classes, fixture=True)['qualification'])

    def test_original_disclosure_chains_have_valid_signatures_but_do_not_prove_tls_rejection(self):
        for chain, root in self.manifest['chain_signature_fixtures'].items():
            with self.subTest(chain=chain):
                result = COLLECTOR.invoke_probe(self.classes, '--fixture-chain',
                    COLLECTOR.FIXTURES / chain, COLLECTOR.FIXTURES / root)
                self.assertTrue(result['signatures_verified'])
                self.assertEqual('UNQUALIFIED', result['qualification'])
                self.assertEqual('NOT_TESTED', result['tls_behavior'])
                with self.assertRaises(ValueError):
                    COLLECTOR.invoke_probe(self.classes, '--fixture-chain',
                        COLLECTOR.FIXTURES / chain, COLLECTOR.FIXTURES / 'emsignrootcag1')


class CertificateBindingTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.inputs = self.root / 'inputs'
        self.inputs.mkdir()
        # Genuine metadata fixture, used only to test binding failures. No
        # certificate measurements are asserted for these historical images.
        with tarfile.open(ROOT / 'scripts/tests/fixtures/buildkit-metadata/ci1290.tar.gz') as archive:
            for member in archive:
                self.assertTrue(member.isfile())
                (self.inputs / member.name).write_bytes(archive.extractfile(member).read())
        (self.inputs / 'source-sha.txt').write_text(SOURCE)
        self.expected = COLLECTOR.image_context(self.inputs, SOURCE)
        self.bundle = self.root / 'bundle'
        self.bundle.mkdir()
        self.envelope = {'schema_version': 1, 'qualification': 'UNQUALIFIED', 'source_sha': SOURCE,
                         'authority_sha256': COLLECTOR.AUTHORITY_SHA256, 'coverage': COLLECTOR.COVERAGE.copy(),
                         'images': {k: {f: v[f] for f in ('native_id', 'configuration_id', 'platform', 'bases')}
                                    for k, v in self.expected['images'].items()}, 'artifacts': {}}
        # Deliberately synthetic identifiers test only the envelope contract.
        for stage, character in (('builder', 'a'), ('runtime', 'b')):
            self.envelope['images'][stage].update({'container_id': character * 64, 'nonce': character * 32,
                'measurement': {'trusted_certificates': 1, 'behavioral_coverage': COLLECTOR.COVERAGE.copy(),
                                'qualification': 'UNQUALIFIED'}})

    def check(self):
        COLLECTOR.write(self.bundle / 'envelope.json', self.envelope)
        return COLLECTOR.check_envelope(self.bundle, self.expected)

    def test_matching_structural_binding_still_has_no_qualification_or_acceptance(self):
        self.assertEqual('UNQUALIFIED', self.check()['qualification'])
        self.assertEqual({'NOT_TESTED'}, set(self.check()['coverage'].values()))

    def test_wrong_source_and_copied_495_evidence_are_rejected(self):
        for source in ('a' * 40, 'c95cf2a904493db4fda4a89dd76ae5030cdd4486'):
            (self.inputs / 'source-sha.txt').write_text(source)
            with self.subTest(source=source), self.assertRaisesRegex(ValueError, 'source identity'):
                COLLECTOR.image_context(self.inputs, SOURCE)

    def test_missing_original_oci_proof_cannot_fall_back_to_version_strings(self):
        (self.inputs / 'builder-identity.tar').unlink()
        with self.assertRaises((OSError, ValueError)):
            COLLECTOR.image_context(self.inputs, SOURCE)

    def test_mismatched_native_image_and_configuration_are_rejected(self):
        path = self.inputs / 'builder-image.json'
        data = COLLECTOR.read(path)
        data[0]['Id'] = 'sha256:' + 'a' * 64
        COLLECTOR.write(path, data)
        with self.assertRaises(ValueError):
            COLLECTOR.image_context(self.inputs, SOURCE)

    def test_wrong_build_stage_is_rejected_by_original_provenance(self):
        path = self.inputs / 'builder-build.json'
        data = COLLECTOR.read(path)
        data['buildx.build.provenance']['invocation']['parameters']['args']['target'] = 'runtime'
        COLLECTOR.write(path, data)
        with self.assertRaisesRegex(ValueError, 'target'):
            COLLECTOR.image_context(self.inputs, SOURCE)

    def test_runtime_evidence_is_required_even_with_valid_builder(self):
        del self.envelope['images']['runtime']
        with self.assertRaises(ValueError):
            self.check()

    def test_wrong_stage_platform_or_config_envelope_is_rejected(self):
        original = copy.deepcopy(self.envelope)
        for key, value in (('native_id', 'sha256:' + 'a' * 64), ('configuration_id', 'sha256:' + 'b' * 64),
                           ('platform', 'linux/arm64')):
            self.envelope = copy.deepcopy(original)
            self.envelope['images']['runtime'][key] = value
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, 'wrong-stage'):
                self.check()

    def test_stale_source_or_authority_envelope_is_rejected(self):
        for key in ('source_sha', 'authority_sha256'):
            original = self.envelope[key]
            self.envelope[key] = '0' * len(original)
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.check()
            self.envelope[key] = original

    def test_missing_altered_extra_and_symlink_artifacts_are_rejected(self):
        path = self.bundle / 'measurement.json'
        path.write_text('{}')
        self.envelope['artifacts'] = COLLECTOR.census(self.bundle)
        path.write_text('{"altered":true}')
        with self.assertRaises(ValueError):
            self.check()
        path.unlink()
        with self.assertRaises(ValueError):
            self.check()
        self.envelope['artifacts'] = {}
        path.symlink_to(self.inputs / 'source-sha.txt')
        with self.assertRaises(ValueError):
            self.check()

    def test_incomplete_behavioral_coverage_cannot_be_claimed_complete(self):
        for field in COLLECTOR.COVERAGE:
            self.envelope['coverage'][field] = 'PASSED'
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.check()
            self.envelope['coverage'][field] = 'NOT_TESTED'
        self.envelope['qualification'] = 'QUALIFIED'
        with self.assertRaises(ValueError):
            self.check()

    def test_no_cve_acceptance_field_or_unresolved_critical_override_is_allowed(self):
        self.envelope['accepted_cves'] = ['CVE-2026-114820', 'CVE-2026-91403']
        with self.assertRaises(ValueError):
            self.check()

    def test_custom_environment_or_application_command_is_rejected_without_secret_echo(self):
        config = {'Env': ['JAVA_HOME=/opt/java/openjdk'], 'WorkingDir': '/app', 'Cmd': None,
                  'Entrypoint': ['java', '-XX:MaxRAMPercentage=75.0', '-Djava.security.egd=file:/dev/./urandom', '-jar', '/app/app.jar']}
        self.assertEqual(2, len(COLLECTOR.launch_options(config, 'runtime')))
        for name in COLLECTOR.OVERRIDES:
            modified = copy.deepcopy(config)
            modified['Env'].append(name + '=PRIVATE_TEST_VALUE')
            with self.subTest(name=name), self.assertRaises(ValueError) as error:
                COLLECTOR.launch_options(modified, 'runtime')
            self.assertNotIn('PRIVATE_TEST_VALUE', str(error.exception))
        config['Entrypoint'].insert(1, '-Djava.security.properties=/custom')
        with self.assertRaises(ValueError):
            COLLECTOR.launch_options(config, 'runtime')

    def test_dirty_preparation_cannot_create_actual_image_evidence(self):
        with patch.object(COLLECTOR, 'run', side_effect=[SOURCE.encode(), b' M scripts/collector.py']):
            with self.assertRaisesRegex(ValueError, 'Clean exact source'):
                COLLECTOR.collect(self.inputs, self.root / 'output', SOURCE)
        self.assertFalse((self.root / 'output').exists())

    def test_command_failure_and_output_limits_do_not_disclose_captured_values(self):
        with self.assertRaises(ValueError) as error:
            COLLECTOR.run([sys.executable, '-c', 'print("PRIVATE_TEST_VALUE");raise SystemExit(2)'])
        self.assertNotIn('PRIVATE_TEST_VALUE', str(error.exception))
        with self.assertRaises(ValueError):
            COLLECTOR.run([sys.executable, '-c', 'print("x"*100000)'], limit=128)
        with self.assertRaises(ValueError):
            COLLECTOR.run([sys.executable, '-c', 'import time;time.sleep(2)'], timeout=0.05)

    def test_changed_authoritative_certificate_bytes_are_rejected(self):
        fixtures = self.root / 'fixtures'
        shutil.copytree(COLLECTOR.FIXTURES, fixtures)
        with patch.object(COLLECTOR, 'FIXTURES', fixtures):
            COLLECTOR.authority()
            (fixtures / 'comodoaaaca').write_bytes(b'altered')
            with self.assertRaisesRegex(ValueError, 'Altered upstream'):
                COLLECTOR.authority()

    def test_retained_class_is_never_executed_even_if_its_receipts_are_rehashed(self):
        classes = COLLECTOR.compile_probe(self.bundle / 'probe')
        (classes / 'CertificateEvidence.class').write_bytes(b'not trusted class bytes')
        receipt = COLLECTOR.read(self.bundle / 'probe/compiler.json')
        receipt['class_sha256'] = COLLECTOR.sha((classes / 'CertificateEvidence.class').read_bytes())
        COLLECTOR.write(self.bundle / 'probe/compiler.json', receipt)
        self.envelope['artifacts'] = COLLECTOR.census(self.bundle)
        COLLECTOR.write(self.bundle / 'envelope.json', self.envelope)
        with patch.object(COLLECTOR, 'invoke_probe', side_effect=AssertionError('Untrusted class executed')) as invoke:
            with self.assertRaisesRegex(ValueError, 'independently compiled'):
                COLLECTOR.validate_bundle(self.bundle, self.expected)
            invoke.assert_not_called()


class OwnedContainerTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.classes = self.root / 'classes'
        self.classes.mkdir()
        self.cid = 'c' * 64
        self.nonce = 'd' * 32
        self.config = {'Env': ['JAVA_HOME=/opt/java/openjdk'], 'WorkingDir': '/app', 'Cmd': None, 'User': 'app:app',
                       'Entrypoint': ['java', '-XX:MaxRAMPercentage=75.0', '-Djava.security.egd=file:/dev/./urandom', '-jar', '/app/app.jar']}
        self.image = {'native_id': 'sha256:' + 'a' * 64, 'configuration_id': 'sha256:' + 'b' * 64,
                      'bases': {'test': 'fixture only'}, 'config': self.config}
        self.inspection = {'Id': self.cid, 'Image': self.image['configuration_id'],
            'Config': {'Labels': {COLLECTOR.LABEL: self.nonce}, 'Image': self.image['native_id'],
                       'Env': self.config['Env'], 'User': self.config['User'], 'WorkingDir': '/app',
                       'Entrypoint': ['/opt/java/openjdk/bin/java'], 'Cmd': [*self.config['Entrypoint'][1:3],
                           '-cp', '/evidence-probe', 'CertificateEvidence']},
            'HostConfig': {'ReadonlyRootfs': True, 'NetworkMode': 'none', 'Privileged': False,
                           'CapDrop': ['ALL'], 'SecurityOpt': ['no-new-privileges'], 'PidsLimit': 64,
                           'Memory': 512 * 1024 * 1024, 'NanoCpus': 1000000000},
            'Mounts': [{'Destination': '/evidence-probe', 'Source': str(self.classes.resolve()), 'RW': False}],
            'State': {'Status': 'exited', 'ExitCode': 0, 'OOMKilled': False}}
        self.commands = []

    def command(self, args, **kwargs):
        self.commands.append(args)
        if args[:2] == ['docker', 'create']:
            return self.cid.encode()
        if args[:3] == ['docker', 'container', 'inspect']:
            return json.dumps([self.inspection]).encode()
        if args[:2] == ['docker', 'start']:
            raise ValueError('Simulated probe timeout')
        if args[:2] == ['docker', 'rm']:
            return self.cid.encode()
        raise AssertionError('Unexpected command')

    def capture(self):
        observed = {'Id': self.image['native_id'], 'Config': self.config}
        with patch.object(COLLECTOR, 'run', side_effect=self.command), \
                patch.object(COLLECTOR.POLICY.IDENTITY, 'inspect_configuration',
                             return_value=(observed, self.image['configuration_id'])):
            return COLLECTOR.capture('runtime', self.image, self.classes, self.root, self.nonce)

    def test_timeout_cleans_only_verified_owned_cid_and_preserves_runtime_isolation(self):
        with self.assertRaisesRegex(ValueError, 'Simulated probe timeout'):
            self.capture()
        self.assertEqual([['docker', 'rm', '--force', '--volumes', self.cid]],
                         [c for c in self.commands if c[:2] == ['docker', 'rm']])
        create = next(c for c in self.commands if c[:2] == ['docker', 'create'])
        self.assertIn('--read-only', create)
        self.assertIn('none', create)
        self.assertIn(self.image['native_id'], create)
        self.assertNotIn('javac', create)

    def test_foreign_label_or_wrong_image_never_authorizes_cleanup(self):
        for field in ('label', 'image'):
            original = copy.deepcopy(self.inspection)
            if field == 'label':
                self.inspection['Config']['Labels'][COLLECTOR.LABEL] = 'foreign'
            else:
                self.inspection['Image'] = 'sha256:' + 'f' * 64
            self.commands = []
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.capture()
            self.assertFalse(any(c[:2] == ['docker', 'rm'] for c in self.commands))
            self.inspection = original

    def test_writable_mount_or_privileged_container_is_rejected_before_probe_start(self):
        for field in ('mount', 'privileged'):
            original = copy.deepcopy(self.inspection)
            if field == 'mount':
                self.inspection['Mounts'][0]['RW'] = True
            else:
                self.inspection['HostConfig']['Privileged'] = True
            self.commands = []
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, 'isolation'):
                self.capture()
            self.assertFalse(any(c[:2] == ['docker', 'start'] for c in self.commands))
            self.inspection = original
