import importlib.util
import copy
import hashlib
import io
import json
import tarfile
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('builder_security', ROOT / 'scripts/validate-builder-security.py')
POLICY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(POLICY)


class BuilderStagePolicyTest(unittest.TestCase):
    def test_current_distinct_jdk_builder_and_jre_runtime_are_reviewed(self):
        POLICY.validate_stages((ROOT / 'Dockerfile').read_text())

    def test_unreviewed_stage_or_identity_requires_a_policy_decision(self):
        dockerfile = (ROOT / 'Dockerfile').read_text()
        for changed in (dockerfile.replace('21-jdk-jammy', '21-jre-jammy'),
                        dockerfile.replace(' AS builder', ' AS runtime'),
                        dockerfile + '\nFROM alpine AS generator\nRUN make artifact\n'):
            with self.subTest(changed=changed), self.assertRaises(ValueError):
                POLICY.validate_stages(changed)


class BuilderEvidencePolicyTest(unittest.TestCase):
    def setUp(self):
        self.packages = {'unzip': '6.0-26ubuntu3.2', 'openssl': '3.0.2-test', 'bash': '5.1-test'}
        self.report = {
            'SchemaVersion': 2, 'ArtifactType': 'container_image', 'ArtifactName': 'enterprise-shop/builder:ci',
            'Trivy': {'Version': '0.75.0'},
            'Metadata': {'ImageID': 'sha256:test', 'OS': {'Family': 'ubuntu', 'Name': '22.04'}},
            'Results': [{'Class': 'os-pkgs', 'Type': 'ubuntu', 'Target': 'Ubuntu',
                         'Packages': [{'Name': k, 'Version': v} for k, v in self.packages.items()],
                         'Vulnerabilities': []}],
        }
        self.bom = {'bomFormat': 'CycloneDX', 'specVersion': '1.7',
                    'metadata': {'component': {'name': 'enterprise-shop/builder:ci',
                        'properties': [{'name': 'aquasecurity:trivy:ImageID', 'value': 'sha256:test'}]}},
                    'components': [{'name': k, 'version': v, 'purl': f'pkg:deb/ubuntu/{k}@{v}'}
                                   for k, v in self.packages.items()]}

    def validate(self, scanner=0, sbom=0, database=0):
        return POLICY.validate_image(self.report, self.bom, self.packages, 'sha256:test', scanner, sbom, database)

    def test_complete_installed_unzip_raw_and_cyclonedx_evidence_passes(self):
        result = self.validate()
        self.assertEqual('6.0-26ubuntu3.2', result['unzip'])
        self.assertEqual([], result['blocked'])
        self.assertEqual(3, result['dpkg_package_count'])

    def test_builder_high_and_critical_fail_without_fix_or_reachability_exceptions(self):
        for severity in ('HIGH', 'CRITICAL'):
            self.report['Results'][0]['Vulnerabilities'] = [
                {'Severity': severity, 'VulnerabilityID': 'CVE-TEST',
                 'PkgName': 'unzip', 'InstalledVersion': self.packages['unzip']}]
            self.assertEqual(severity, self.validate()['blocked'][0]['severity'])

    def test_runtime_or_postgres_gosu_exceptions_cannot_exempt_builder_findings(self):
        self.report['Results'].append({'Class': 'lang-pkgs', 'Type': 'gobinary',
            'Target': 'usr/local/bin/gosu', 'Packages': [{'Name': 'stdlib', 'Version': '1.24.0'}],
            'Vulnerabilities': [{'Severity': 'CRITICAL', 'VulnerabilityID': 'CVE-2025-68121',
                                 'PkgName': 'stdlib', 'InstalledVersion': '1.24.0'}]})
        self.assertEqual('CVE-2025-68121', self.validate()['blocked'][0]['id'])

    def test_missing_or_malformed_raw_evidence_fails(self):
        for report in ({}, [], {'SchemaVersion': 2}, {**self.report, 'Results': []},
                       {**self.report, 'Trivy': {'Version': 'unknown'}}):
            self.report = report
            with self.assertRaises((ValueError, TypeError, KeyError)):
                self.validate()

    def test_scanner_database_or_sbom_failure_cannot_become_clean(self):
        for statuses in ((1, 0, 0), (0, 1, 0), (0, 0, 1), (True, 0, 0)):
            with self.subTest(statuses=statuses), self.assertRaises(ValueError):
                self.validate(*statuses)

    def test_missing_wrong_or_incomplete_builder_sbom_fails(self):
        for bom in ({}, {**self.bom, 'components': []},
                    {**self.bom, 'components': self.bom['components'][1:]},
                    {**self.bom, 'metadata': {'component': {'name': 'enterprise-shop/app:ci'}}}):
            self.bom = bom
            with self.assertRaises((ValueError, KeyError)):
                self.validate()

    def test_omitted_raw_unzip_or_changed_version_fails(self):
        self.report['Results'][0]['Packages'].pop(0)
        with self.assertRaises(ValueError):
            self.validate()

    def test_wrong_image_or_os_cannot_reuse_clean_evidence(self):
        self.report['Metadata']['ImageID'] = 'sha256:other'
        with self.assertRaises(ValueError):
            self.validate()

    def test_malformed_vulnerability_container_cannot_become_clean(self):
        for malformed in ({}, 'clean', False):
            self.report['Results'][0]['Vulnerabilities'] = malformed
            with self.assertRaises(ValueError):
                self.validate()

    def test_debian_epoch_and_release_are_part_of_the_scanned_identity(self):
        self.assertEqual('1:3.8-0ubuntu2.1', POLICY.package_version(
            {'Version': '3.8', 'Epoch': 1, 'Release': '0ubuntu2.1'}))
        self.assertEqual('6.0-26ubuntu3.2', POLICY.package_version(
            {'Version': '6.0', 'Release': '26ubuntu3.2'}))

    def test_installed_dpkg_inventory_is_not_a_hand_maintained_tool_list(self):
        text = '\n'.join(f'{k}\t{v}\tamd64\tinstalled' for k, v in self.packages.items())
        self.assertEqual(self.packages, POLICY.dpkg_inventory(text))
        with self.assertRaises(ValueError):
            POLICY.dpkg_inventory('bash\t5.1\tamd64\tinstalled')

    def test_duplicate_keys_or_truncated_json_fail(self):
        import tempfile
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'report.json'
            for payload in ('{"Results":[],"Results":[]}', '{'):
                path.write_text(payload)
                with self.assertRaises(ValueError):
                    POLICY.read_json(path)


class TemurinAdvisoryPolicyTest(unittest.TestCase):
    def vdr(self, bound='21.0.12', score=7.5):
        return {'bomFormat': 'CycloneDX', 'specVersion': '1.4',
                'metadata': {'component': {'name': 'Eclipse Temurin'}},
                'vulnerabilities': [{'id': 'CVE-2026-12345',
                    'affects': [{'ref': 'pkg:github/openjdk/jdk',
                                 'versions': [{'range': 'vers:generic/8u472|' + bound, 'status': 'affected'}]}],
                    'ratings': [{'method': 'CVSSv31', 'score': score}]}]}

    def test_java21_vendor_and_version_are_required(self):
        self.assertEqual('21.0.12.1', POLICY.java_version('IMPLEMENTOR="Eclipse Adoptium"\nJAVA_VERSION="21.0.12.1"\n'))
        for release in ('', 'IMPLEMENTOR="other"\nJAVA_VERSION="21.0.12"',
                        'IMPLEMENTOR="Eclipse Adoptium"\nJAVA_VERSION="22.0.1"'):
            with self.assertRaises(ValueError):
                POLICY.java_version(release)

    def test_same_feature_affected_version_and_earlier_block_high_or_critical(self):
        for version in ('21.0.12', '21.0.11', '21.0.1'):
            for score, severity in ((7.5, 'HIGH'), (9.8, 'CRITICAL')):
                self.assertEqual(severity, POLICY.jdk_findings(self.vdr(score=score), version)[0]['severity'])

    def test_fixed_patch_other_feature_or_low_severity_does_not_block(self):
        self.assertEqual([], POLICY.jdk_findings(self.vdr(), '21.0.12.1'))
        self.assertEqual([], POLICY.jdk_findings(self.vdr(bound='17.0.12'), '21.0.1'))
        self.assertEqual([], POLICY.jdk_findings(self.vdr(score=6.9), '21.0.1'))

    def test_missing_advisories_or_unsupported_applicability_fail_closed(self):
        for vdr in ({}, {**self.vdr(), 'vulnerabilities': []}, self.vdr(bound='>=21.0.1')):
            with self.assertRaises((ValueError, KeyError)):
                POLICY.jdk_findings(vdr, '21.0.12.1')

    def test_incomplete_duplicate_cannot_erase_complete_advisory_bounds(self):
        import copy
        vdr = self.vdr()
        vdr['vulnerabilities'][0]['description'] = 'The same vendor advisory'
        duplicate = copy.deepcopy(vdr['vulnerabilities'][0])
        duplicate['affects'][0].pop('versions')
        vdr['vulnerabilities'].append(duplicate)
        self.assertEqual(1, len(POLICY.jdk_findings(vdr, '21.0.1')))
        duplicate['description'] = 'Conflicting applicability'
        with self.assertRaises(ValueError):
            POLICY.jdk_findings(vdr, '21.0.1')

    def test_missing_or_nonfinite_severity_fails_closed(self):
        for score in (None, float('nan'), 11):
            with self.assertRaises(ValueError):
                POLICY.jdk_findings(self.vdr(score=score), '21.0.1')


class BuildProvenanceTest(unittest.TestCase):
    def document(self):
        digest = 'a' * 64
        return {'containerimage.config.digest': 'sha256:image', 'buildx.build.provenance': {
            'buildType': 'https://mobyproject.org/buildkit@v1',
            'invocation': {'environment': {'platform': 'linux/amd64'},
                           'configSource': {'entryPoint': 'Dockerfile'},
                           'parameters': {'args': {'target': 'builder'}}},
            'materials': [{'uri': 'pkg:docker/eclipse-temurin@21-jdk-jammy?platform=linux%2Famd64',
                           'digest': {'sha256': digest}}],
            'buildConfig': {'llbDefinition': [{'op': {'Op': {'source': {
                'identifier': 'docker-image://docker.io/library/eclipse-temurin:21-jdk-jammy@sha256:' + digest,
                'attrs': {'image.resolvemode': 'pull'}}}}}]}}}

    def test_exact_base_target_platform_and_fresh_pull_are_required(self):
        document = self.document()
        self.assertEqual({'21-jdk-jammy': 'sha256:' + 'a' * 64},
                         POLICY.build_provenance(document, 'sha256:image', True))
        document['buildx.build.provenance']['buildConfig']['llbDefinition'][0]['op']['Op']['source']['attrs']['image.resolvemode'] = 'default'
        with self.assertRaises(ValueError):
            POLICY.build_provenance(document, 'sha256:image', True)
        with self.assertRaises(ValueError):
            POLICY.build_provenance(self.document(), 'sha256:other', True)

    def test_native_descriptor_requires_original_metadata_before_config_provenance(self):
        import io
        import tarfile
        import tempfile
        native_id = 'sha256:22a761becced308c3ef0e30f3a73ca236012b1f02a9109ddb2d2f418dd91a8ff'
        config_id = 'sha256:5069b87377ce91b47890d84793e8f68efe42e927f0b423fe958b7c428ef30379'
        with tarfile.open(ROOT / 'scripts/tests/fixtures/docker-identity/docker29-image.tar.gz') as archive:
            _, _, _, metadata = POLICY.IDENTITY.configuration_metadata(archive, native_id)
        with tempfile.TemporaryDirectory() as temporary:
            proof = Path(temporary) / 'identity.tar'
            with tarfile.open(proof, 'w') as retained:
                for name, raw in metadata.items():
                    member = tarfile.TarInfo(name)
                    member.size = len(raw)
                    retained.addfile(member, io.BytesIO(raw))
            document = self.document()
            document['containerimage.config.digest'] = config_id
            # The former native-ID/config-ID comparison fails on real Docker 29 bytes.
            with self.assertRaises(ValueError):
                POLICY.build_provenance(document, native_id, True)
            observed = {'Id': native_id, 'Os': 'linux', 'Architecture': 'amd64'}
            actual, bases = POLICY.image_provenance(document, observed, proof, True)
            self.assertEqual(config_id, actual)
            self.assertEqual({'21-jdk-jammy': 'sha256:' + 'a' * 64}, bases)
            document['containerimage.config.digest'] = 'sha256:' + '0' * 64
            with self.assertRaises(ValueError):
                POLICY.image_provenance(document, observed, proof, True)
            with self.assertRaises(ValueError):
                POLICY.image_provenance(self.document(), {**observed, 'Id': 'sha256:' + '0' * 64}, proof, True)


class BuilderPublisherIdentityTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        fixture = ROOT / 'scripts/tests/fixtures/buildkit-metadata/ci1290.tar.gz'
        self.assertEqual('f0a696cf28c94dc1638d6b518d805c50625e1eb64b8a713b5dc51e68054ab210',
                         hashlib.sha256(fixture.read_bytes()).hexdigest())
        with tarfile.open(fixture) as saved:
            expected = {f'{name}-{suffix}' for name in ('builder', 'app')
                        for suffix in ('build.json', 'image.json', 'identity.tar')}
            self.assertEqual(expected, {m.name for m in saved})
            for member in saved.getmembers():
                self.assertTrue(member.isfile())
                (self.root / member.name).write_bytes(saved.extractfile(member).read())

    def inputs(self, name='builder'):
        return (POLICY.read_json(self.root / f'{name}-build.json'),
                POLICY.read_json(self.root / f'{name}-image.json')[0],
                self.root / f'{name}-identity.tar')

    def validate(self, name='builder'):
        return POLICY.image_provenance(*self.inputs(name), name == 'builder')

    def write_proof(self, files):
        path = self.root / 'changed.tar'
        with tarfile.open(path, 'w') as saved:
            for name, data in files.items():
                member = tarfile.TarInfo(name)
                member.size = len(data)
                saved.addfile(member, io.BytesIO(data))
        return path

    def proof_files(self, path):
        with tarfile.open(path) as saved:
            return {m.name: saved.extractfile(m).read() for m in saved if m.isfile()}

    def test_actual_ci1290_builder_and_runtime_descriptor_only_publication(self):
        expected = {'builder': 'sha256:df149d15bf89d247d666287f385251902156ddd8527cb10c780dc32fcd80139c',
                    'app': 'sha256:dee40abb5301270b2282c34658db668a88d6cff1f78c575f325dbfab16b8fe1d'}
        for name, config in expected.items():
            with self.subTest(name=name):
                document, observed, proof = self.inputs(name)
                self.assertNotIn('containerimage.config.digest', document)
                self.assertNotEqual(config, observed['Id'])
                actual, bases = self.validate(name)
                self.assertEqual(config, actual)
                self.assertIn('21-jdk-jammy', bases)

    def test_existing_configuration_only_and_agreeing_dual_publication(self):
        for name in ('builder', 'app'):
            document, observed, proof = self.inputs(name)
            config = POLICY.IDENTITY.configuration_evidence(proof, observed['Id'])
            document['containerimage.config.digest'] = config
            with self.subTest(name=name, form='dual'):
                self.assertEqual(config, POLICY.image_provenance(document, observed, proof, name == 'builder')[0])
            document.pop('containerimage.descriptor')
            document.pop('containerimage.digest')
            with self.subTest(name=name, form='configuration'):
                self.assertEqual(config, POLICY.image_provenance(document, observed, proof, name == 'builder')[0])

    def test_classic_configuration_publication_still_requires_original_bytes(self):
        config = 'sha256:5069b87377ce91b47890d84793e8f68efe42e927f0b423fe958b7c428ef30379'
        fixture = ROOT / 'scripts/tests/fixtures/docker-identity/docker29-image.tar.gz'
        with tarfile.open(fixture) as saved:
            _, _, _, metadata = POLICY.IDENTITY.configuration_metadata(saved, config)
        proof = self.write_proof(metadata)
        document = BuildProvenanceTest().document()
        document['containerimage.config.digest'] = config
        document['containerimage.digest'] = config
        observed = {'Id': config, 'Os': 'linux', 'Architecture': 'amd64'}
        self.assertEqual(config, POLICY.image_provenance(document, observed, proof, True)[0])
        document['containerimage.digest'] = 'sha256:' + '0' * 64
        with self.assertRaises(ValueError):
            POLICY.image_provenance(document, observed, proof, True)

    def test_conflicting_configuration_cannot_fall_back_to_a_correct_descriptor(self):
        for name in ('builder', 'app'):
            for wrong in (None, '', 'sha256:' + '0' * 64):
                document, observed, proof = self.inputs(name)
                document['containerimage.config.digest'] = wrong
                with self.subTest(name=name, value=wrong), self.assertRaises(ValueError):
                    POLICY.image_provenance(document, observed, proof, name == 'builder')

    def test_missing_or_malformed_publisher_authority_fails_closed(self):
        document, observed, proof = self.inputs()
        for change in ('descriptor', 'all', None, {}, []):
            changed = copy.deepcopy(document)
            if change in ('descriptor', 'all'):
                changed.pop('containerimage.descriptor')
                if change == 'all':
                    changed.pop('containerimage.digest')
            else:
                changed['containerimage.descriptor'] = change
                changed['containerimage.config.digest'] = POLICY.IDENTITY.configuration_evidence(proof, observed['Id'])
            with self.subTest(change=change), self.assertRaises(ValueError):
                POLICY.image_provenance(changed, observed, proof, True)

    def test_descriptor_digest_media_type_size_and_conflicting_digest_are_checked(self):
        document, observed, proof = self.inputs()
        changes = [('digest', 'sha256:' + '0' * 64), ('mediaType', 'application/vnd.oci.image.config.v1+json'),
                   ('size', 1), ('size', True), ('size', 856.0), ('size', -1)]
        for key, value in changes:
            for dual in (False, True):
                changed = copy.deepcopy(document)
                changed['containerimage.descriptor'][key] = value
                if dual:
                    changed['containerimage.config.digest'] = POLICY.IDENTITY.configuration_evidence(proof, observed['Id'])
                with self.subTest(key=key, value=value, dual=dual), self.assertRaises(ValueError):
                    POLICY.image_provenance(changed, observed, proof, True)
        document['containerimage.digest'] = 'sha256:' + '0' * 64
        with self.assertRaises(ValueError):
            POLICY.image_provenance(document, observed, proof, True)

    def test_optional_publisher_size_uses_the_original_descriptor_bytes(self):
        document, observed, proof = self.inputs()
        document['containerimage.descriptor'].pop('size')
        self.assertEqual('sha256:df149d15bf89d247d666287f385251902156ddd8527cb10c780dc32fcd80139c',
                         POLICY.image_provenance(document, observed, proof, True)[0])

    def test_verified_descriptor_authority_does_not_require_redundant_digest(self):
        document, observed, proof = self.inputs()
        document.pop('containerimage.digest')
        self.assertEqual('sha256:df149d15bf89d247d666287f385251902156ddd8527cb10c780dc32fcd80139c',
                         POLICY.image_provenance(document, observed, proof, True)[0])

    def test_unreviewed_descriptor_fields_fail_closed(self):
        document, observed, proof = self.inputs()
        document['containerimage.descriptor']['platform'] = {'os': 'windows', 'architecture': 'amd64'}
        with self.assertRaises(ValueError):
            POLICY.image_provenance(document, observed, proof, True)

    def test_configuration_digest_is_not_a_descriptor_only_authority(self):
        document, observed, proof = self.inputs()
        config = POLICY.IDENTITY.configuration_evidence(proof, observed['Id'])
        document['containerimage.digest'] = config
        document['containerimage.descriptor']['digest'] = config
        with self.assertRaises(ValueError):
            POLICY.image_provenance(document, observed, proof, True)

    def test_missing_stale_or_wrong_image_original_evidence_fails(self):
        document, observed, proof = self.inputs()
        for path in (self.root / 'missing.tar', self.inputs('app')[2], self.write_proof({})):
            with self.subTest(proof=path), self.assertRaises((ValueError, OSError)):
                POLICY.image_provenance(document, observed, path, True)
        with self.assertRaises(ValueError):
            POLICY.image_provenance(document, {**observed, 'Id': 'sha256:' + '0' * 64}, proof, True)

    def test_original_index_manifest_configuration_and_layer_descriptor_tampering_fails(self):
        document, observed, proof = self.inputs()
        files = self.proof_files(proof)
        with tarfile.open(proof) as saved:
            _, config, manifest, _ = POLICY.IDENTITY.configuration_metadata(saved, observed['Id'])
        selected = ['blobs/sha256/' + observed['Id'].split(':')[1],
                    'blobs/sha256/' + manifest['config']['digest'].split(':')[1],
                    next(n for n, raw in files.items() if n.startswith('blobs/')
                         and POLICY.IDENTITY.image_json(raw) == manifest)]
        for name in selected:
            changed = dict(files)
            changed[name] += b' '
            with self.subTest(member=name), self.assertRaises(ValueError):
                POLICY.image_provenance(document, observed, self.write_proof(changed), True)
        changed = dict(files)
        value = copy.deepcopy(manifest)
        value['layers'][0]['digest'] = 'sha256:' + '0' * 64
        changed[selected[-1]] = json.dumps(value).encode()
        with self.assertRaises(ValueError):
            POLICY.image_provenance(document, observed, self.write_proof(changed), True)

    def test_self_consistent_but_ambiguous_platform_index_fails(self):
        document, observed, proof = self.inputs()
        files = self.proof_files(proof)
        old_name = 'blobs/sha256/' + observed['Id'].split(':')[1]
        index = POLICY.IDENTITY.image_json(files.pop(old_name))
        platform = next(d for d in index['manifests'] if d.get('platform', {}).get('os') == 'linux')
        index['manifests'].append(copy.deepcopy(platform))
        raw = json.dumps(index).encode()
        new_id = 'sha256:' + hashlib.sha256(raw).hexdigest()
        outer = POLICY.IDENTITY.image_json(files['index.json'])
        next(d for d in outer['manifests'] if d['digest'] == observed['Id']).update(digest=new_id, size=len(raw))
        files['index.json'] = json.dumps(outer).encode()
        files['blobs/sha256/' + new_id.split(':')[1]] = raw
        document['containerimage.descriptor'].update(digest=new_id, size=len(raw))
        document['containerimage.digest'] = new_id
        with self.assertRaisesRegex(ValueError, 'ambiguous linux/amd64'):
            POLICY.image_provenance(document, {**observed, 'Id': new_id}, self.write_proof(files), True)

    def test_wrong_platform_target_or_base_is_rejected(self):
        document, observed, proof = self.inputs()
        for changed in ({**observed, 'Os': 'windows'}, {**observed, 'Architecture': 'arm64'}):
            with self.subTest(observed=changed), self.assertRaises(ValueError):
                POLICY.image_provenance(document, changed, proof, True)
        with self.assertRaises(ValueError):
            POLICY.image_provenance(document, observed, proof, False)
        next(m for m in document['buildx.build.provenance']['materials']
             if '21-jdk-jammy' in m['uri'])['uri'] = 'pkg:docker/unreviewed@base'
        with self.assertRaises(ValueError):
            POLICY.image_provenance(document, observed, proof, True)

    def test_individually_valid_but_different_builder_and_runtime_bases_fail(self):
        document, _, _ = self.inputs('app')
        provenance = document['buildx.build.provenance']
        material = next(m for m in provenance['materials'] if '21-jdk-jammy' in m['uri'])
        old = material['digest']['sha256']
        material['digest']['sha256'] = '0' * 64
        for entry in provenance['buildConfig']['llbDefinition']:
            source = entry.get('op', {}).get('Op', {}).get('source')
            if source and '21-jdk-jammy@sha256:' + old in source.get('identifier', ''):
                source['identifier'] = source['identifier'].replace(old, '0' * 64)
        (self.root / 'app-build.json').write_text(json.dumps(document))
        for name in ('trivy-raw.json', 'builder.cdx.json'):
            (self.root / name).write_text('{}')
        with self.assertRaisesRegex(ValueError, 'Builder base changed'):
            POLICY.evaluate(self.root)

    def test_full_layer_bytes_remain_checked_by_the_original_export_verifier(self):
        fixture = ROOT / 'scripts/tests/fixtures/docker-identity/docker29-image.tar.gz'
        files = self.proof_files(fixture)
        layer = json.loads(files['manifest.json'])[0]['Layers'][0]
        files[layer] += b'changed'
        with tarfile.open(self.write_proof(files)) as changed, self.assertRaises(ValueError):
            POLICY.IDENTITY.saved_configuration(changed,
                'sha256:22a761becced308c3ef0e30f3a73ca236012b1f02a9109ddb2d2f418dd91a8ff')

    def test_stale_original_source_head_fails_before_package_acceptance(self):
        (self.root / 'trivy-raw.json').write_text('{}')
        (self.root / 'builder.cdx.json').write_text('{}')
        (self.root / 'source-sha.txt').write_text('a' * 40)
        with patch.object(POLICY.subprocess, 'check_output', return_value='b' * 40), \
                self.assertRaisesRegex(ValueError, 'stale exact source'):
            POLICY.evaluate(self.root)

    def test_package_reports_use_the_original_verified_native_image_id(self):
        for name in ('trivy-raw.json', 'builder.cdx.json'):
            (self.root / name).write_text('{}')
        (self.root / 'builder-dpkg.tsv').write_text('unused')
        for name in ('scanner-exit-status.txt', 'sbom-exit-status.txt', 'database-exit-status.txt'):
            (self.root / name).write_text('0')
        (self.root / 'source-sha.txt').write_text('a' * 40)
        with patch.object(POLICY.subprocess, 'check_output', return_value='a' * 40), \
                patch.object(POLICY, 'dpkg_inventory', return_value={}), \
                patch.object(POLICY, 'validate_image', side_effect=RuntimeError('package boundary')) as validate, \
                self.assertRaisesRegex(RuntimeError, 'package boundary'):
            POLICY.evaluate(self.root)
        self.assertEqual(self.inputs()[1]['Id'], validate.call_args.args[3])
        self.assertNotEqual('sha256:df149d15bf89d247d666287f385251902156ddd8527cb10c780dc32fcd80139c',
                            validate.call_args.args[3])


class TemurinEvidenceFetchTest(unittest.TestCase):
    def test_vendor_checksum_and_asset_authority_are_required(self):
        import hashlib
        import json
        import tempfile
        from unittest.mock import patch
        tag = 'temurin-vdr-06-10-2026-37483977503'
        name = tag + '.json'
        prefix = f'https://github.com/{POLICY.VDR_REPOSITORY}/releases/download/{tag}/'
        payload = json.dumps(TemurinAdvisoryPolicyTest().vdr()).encode()
        digest = hashlib.sha256(payload).hexdigest()
        release = {'tag_name': tag, 'draft': False, 'prerelease': False, 'id': 12,
                   'published_at': '2026-10-06T15:02:09Z', 'immutable': False,
                   'assets': [{'name': name, 'id': 34, 'browser_download_url': prefix + name,
                               'digest': 'sha256:' + digest},
                              {'name': tag + '.sha256', 'browser_download_url': prefix + tag + '.sha256'}]}
        for checksum, passes in ((digest, True), ('0' * 64, False)):
            with tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary)
                (directory / 'policy-result.json').write_text('stale')
                with patch.object(POLICY, 'download', side_effect=[json.dumps(release).encode(),
                        payload, f'{checksum}  {name}\n'.encode()]):
                    if passes:
                        POLICY.fetch_jdk(directory)
                        provenance = POLICY.read_json(directory / 'jdk-advisory-provenance.json')
                        self.assertEqual(digest, provenance['sha256'])
                        self.assertFalse(provenance['upstream_immutable'])
                    else:
                        with self.assertRaises(ValueError):
                            POLICY.fetch_jdk(directory)
                        self.assertFalse((directory / 'temurin-vdr.json').exists())
                self.assertFalse((directory / 'policy-result.json').exists())

    def test_transport_failure_preserves_failure_and_removes_stale_advisory_evidence(self):
        import tempfile
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            for name in ('policy-result.json', 'temurin-vdr.json', 'jdk-advisory-provenance.json'):
                (directory / name).write_text('stale')
            with patch.object(POLICY, 'download', side_effect=OSError('network unavailable')):
                with self.assertRaises(OSError):
                    POLICY.fetch_jdk(directory)
            self.assertFalse((directory / 'temurin-vdr.json').exists())

    def test_missing_builder_sbom_cannot_reuse_a_previous_policy_success(self):
        import tempfile
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / 'policy-result.json').write_text('{"blocked": []}')
            (directory / 'trivy-raw.json').write_text('{}')
            with self.assertRaises(FileNotFoundError):
                POLICY.evaluate(directory)
            self.assertFalse((directory / 'policy-result.json').exists())


class BuilderCiPolicyTest(unittest.TestCase):
    def setUp(self):
        self.workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        self.job = self.workflow.split('  container-security:\n', 1)[1].split('  deploy-pages:\n', 1)[0]
        self.scanner = (ROOT / 'scripts/scan-docker-builder.sh').read_text()

    def test_scheduled_protected_master_and_pr_source_coverage_remain_readonly(self):
        self.assertIn('cron: "23 4 * * 1"', self.workflow)
        self.assertNotIn('    if:', self.job.split('    steps:', 1)[0])
        self.assertIn("github.event_name == 'schedule' && 'master' || github.event.pull_request.head.sha || github.ref", self.job)
        self.assertIn('persist-credentials: false', self.job)
        for disallowed in ('contents: write', 'secrets.', 'pull_request_target', 'continue-on-error'):
            self.assertNotIn(disallowed, self.job)

    def test_native_fresh_builder_target_and_separate_runtime_output_are_required(self):
        self.assertIn('docker buildx build --pull --platform linux/amd64 --target builder --load --tag enterprise-shop/builder:ci', self.job)
        self.assertIn('docker buildx build --pull --platform linux/amd64 --load --tag enterprise-shop/app:ci', self.job)
        self.assertIn('BUILDX_METADATA_PROVENANCE: max', self.job)
        for command in ('python scripts/validate-builder-security.py stages',
                        'bash scripts/record-docker-builder.sh', 'bash scripts/scan-docker-builder.sh',
                        'python scripts/validate-builder-security.py fetch-jdk',
                        'python scripts/validate-builder-security.py evidence'):
            self.assertIn(command, self.job)

    def test_raw_report_and_cyclonedx_are_uploaded_before_enforcement_even_on_failure(self):
        upload = self.job.split('- name: Upload builder security evidence', 1)[1].split('- name:', 1)[0]
        self.assertIn('if: always()', upload)
        self.assertIn('if-no-files-found: error', upload)
        self.assertIn('include-hidden-files: true', upload)
        self.assertLess(self.job.index('name: Upload builder security evidence'), self.job.index('validate-builder-security.py evidence'))
        self.assertIn('--format json --output /evidence/trivy-raw.json', self.scanner)
        self.assertIn('--format cyclonedx --output /evidence/builder.cdx.json', self.scanner)

    def test_pinned_trivy_same_database_and_unfiltered_separate_builder_policy(self):
        self.assertIn('TRIVY_IMAGE: local/enterprise-shop-trivy:sha256-17c17d15f3ae47ab5bc2b2136e68b52d43c3825280bc7d536b6ab341274091b7', self.job)
        self.assertIn('--list-all-pkgs --exit-code 0', self.scanner)
        self.assertIn('--ignorefile /dev/null', self.scanner)
        self.assertIn('image --download-db-only', self.scanner)
        self.assertIn('scanner-exit-status.txt', self.scanner)
        self.assertIn('sbom-exit-status.txt', self.scanner)
        self.assertIn('database-exit-status.txt', self.scanner)
        for disallowed in ('--ignore-unfixed', '.trivyignore', '--severity', '|| true'):
            self.assertNotIn(disallowed, self.scanner)


if __name__ == '__main__':
    unittest.main()
