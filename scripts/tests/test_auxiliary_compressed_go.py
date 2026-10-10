"""Offline contract mutations for measured compressed Go executable evidence."""
import copy
import hashlib
import importlib.util
import io
import json
import struct
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit

ROOT = Path(__file__).parents[2]
SPEC = importlib.util.spec_from_file_location('policy', ROOT / 'scripts/validate-auxiliary-containers.py')
POLICY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(POLICY)
BINARY = POLICY.BINARY


class CompressedGoEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.image = copy.deepcopy(POLICY.read(POLICY.CONTRACT)['comparisons']['official_ryuk'])
        self.transform = self.image['evidence_transform']
        header = bytearray(64)
        header[:7] = b'\x7fELF\x02\x01\x01'
        struct.pack_into('<HH', header, 16, 2, 62)
        self.compressed = bytes(header) + b'UPX!' + self.transform['upx_marker'].encode()
        self.payload = bytes(header) + b'offline binary fixture'
        self.image['identity']['sha256'] = BINARY.sha(self.compressed)
        self.transform['decompressed_sha256'] = BINARY.sha(self.payload)
        self.module = 'github.com/example/reaper'
        self.dependency = 'github.com/example/client'
        text = ('/payload/ryuk: go1.23.12\n\tpath\t' + self.module + '\n\tmod\t' + self.module + '\t(devel)\t\n'
                '\tdep\t' + self.dependency + '\tv1.2.3\th1:fixture\n'
                '\tbuild\t-buildmode=exe\n\tbuild\tCGO_ENABLED=0\n\tbuild\tGOARCH=amd64\n\tbuild\tGOOS=linux\n')
        self.put('buildinfo.txt', text.encode())
        self.transform['buildinfo'] = BINARY.buildinfo(text)
        self.put('compressed.bin', self.compressed)
        self.put('analysis/bin/ryuk', self.payload)
        self.put('tools/upx', b'offline UPX fixture')
        self.put('tools/upx.tar.xz', b'offline archive fixture')
        self.transform['tool']['binary_sha256'] = BINARY.sha(b'offline UPX fixture')
        self.transform['tool']['archive_sha256'] = BINARY.sha(b'offline archive fixture')
        self.put('version.txt', b'upx 5.2.1\n')
        self.transform['filesystem'] = {'bin/ryuk': {'sha256': BINARY.sha(self.compressed), 'size': len(self.compressed)}}
        self.put_json('filesystem-census.json', self.transform['filesystem'])
        self.transform['source_files'] = {}
        for name, data in {'go.mod': 'module ' + self.module + '\nrequire (\n\t' + self.dependency + ' v1.2.3\n)\n',
                           'go.sum': self.dependency + ' v1.2.3 h1:fixture\n'}.items():
            self.put('upstream/' + name, data.encode())
            self.transform['source_files'][name] = BINARY.sha(data.encode())
        self.put_json('image-config.json', {'architecture': 'amd64', 'os': 'linux'})
        self.transform['image_id'] = 'sha256:' + self.digest('image-config.json')
        self.put_json('registry-manifest.raw.json', {'config': {'digest': self.transform['image_id']}})
        self.transform['resolved_digest'] = 'sha256:' + self.digest('registry-manifest.raw.json')
        upstream = self.transform['upstream_provenance']
        statement = {'predicateType': 'https://slsa.dev/provenance/v0.2',
                     'subject': [{'digest': {'sha256': self.transform['resolved_digest'].split(':')[1]}}],
                     'predicate': {'builder': {'id': upstream['builder']}, 'metadata': {
                         'https://mobyproject.org/buildkit@v1#metadata': {'vcs': {
                             'revision': self.transform['source_commit'], 'source': upstream['repository']}}}}}
        self.put_json('upstream-provenance.json', statement)
        upstream['statement_digest'] = 'sha256:' + self.digest('upstream-provenance.json')
        self.put_json('upstream-provenance-manifest.json', {'layers': [{'digest': upstream['statement_digest']}]})
        upstream['manifest_digest'] = 'sha256:' + self.digest('upstream-provenance-manifest.json')
        self.put_json('registry-index.raw.json', {'manifests': [{'digest': self.transform['resolved_digest']},
                                                             {'digest': upstream['manifest_digest']}]})
        self.image['reference'] = 'docker.io/testcontainers/ryuk:0.14.0@sha256:' + self.digest('registry-index.raw.json')
        self.put('govulncheck.json', (json.dumps({'config': {'scan_mode': 'binary'}}, indent=2)
            + '\n' + json.dumps({'SBOM': {'go_version': 'go1.23.12', 'roots': [self.module],
                'modules': [{'path': self.dependency, 'version': 'v1.2.3'}]}}, indent=2)).encode())
        now = datetime.now(timezone.utc)
        self.evidence = {'reference': self.image['reference'], 'platform': 'linux/amd64',
                         'identity': self.image['identity'], 'image_id': self.transform['image_id'],
                         'resolved_digest': self.transform['resolved_digest'], 'artifact_name': '/analysis',
                         'database_status': 0, 'scanner_status': 0, 'sbom_status': 0,
                         'independent_scanner_integrity': False, 'installed_os_packages': {},
                         'timestamp': now.isoformat(), 'database': {'Version': 2,
                             'UpdatedAt': (now - timedelta(hours=1)).isoformat(),
                             'NextUpdate': (now + timedelta(hours=4)).isoformat()}}
        packages = [{'Name': self.module, 'Relationship': 'root', 'Identifier': {'PURL': 'pkg:golang/' + self.module}},
                    {'Name': 'stdlib', 'Version': 'v1.23.12', 'Identifier': {'PURL': 'pkg:golang/stdlib@v1.23.12'}},
                    {'Name': self.dependency, 'Version': 'v1.2.3', 'Identifier': {'PURL': 'pkg:golang/' + self.dependency + '@v1.2.3'}}]
        self.report = {'SchemaVersion': 2, 'Trivy': {'Version': '0.75.0'}, 'ArtifactName': '/analysis',
                       'ArtifactType': 'filesystem', 'Results': [{'Target': 'bin/ryuk', 'Class': 'lang-pkgs',
                                                                'Type': 'gobinary', 'Packages': packages}]}
        self.bom = {'bomFormat': 'CycloneDX', 'specVersion': '1.7', 'metadata': {'component': {'name': '/analysis'}},
                    'components': [{'purl': p['Identifier']['PURL']} for p in packages]}
        self.receipt = {'method': self.transform['method'], 'executed_image': self.image['reference'],
                        'compressed_sha256': BINARY.sha(self.compressed), 'decompressed_sha256': BINARY.sha(self.payload),
                        'tool': self.transform['tool'], 'analysis_image': self.transform['analysis_image'],
                        'statuses': dict.fromkeys(('version', 'list', 'test', 'decompress', 'repeat', 'buildinfo'), 0),
                        'buildinfo': self.transform['buildinfo'], 'filesystem': self.transform['filesystem'],
                        'upx_marker': self.transform['upx_marker'], 'payload_executed': False,
                        'govulncheck_status': 0, 'govulncheck_sha256': self.digest('govulncheck.json')}
        self.reviewed_image = copy.deepcopy(self.image)

    def put(self, name, data):
        path = self.directory / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)

    def put_json(self, name, data):
        self.put(name, json.dumps(data).encode())

    def digest(self, name):
        return hashlib.sha256((self.directory / name).read_bytes()).hexdigest()

    def validate(self):
        self.put_json('raw.json', self.report)
        self.put_json('sbom.cdx.json', self.bom)
        self.receipt['raw_sha256'] = self.digest('raw.json')
        self.receipt['sbom_sha256'] = self.digest('sbom.cdx.json')
        self.put_json('transformation.json', self.receipt)
        return POLICY.validate(self.image, self.evidence, self.report, self.bom, '0.75.0', self.directory)

    def test_complete_readback_retains_both_hashes_and_execution_identity(self):
        result = self.validate()
        self.assertEqual(3, result['package_count'])
        self.assertTrue(result['readback_complete'])
        self.assertEqual(self.image['reference'], result['reference'])
        self.assertEqual(BINARY.sha(self.compressed), result['transformation']['compressed_sha256'])
        self.assertEqual(BINARY.sha(self.payload), result['transformation']['decompressed_sha256'])

    def test_missing_compressed_hash_blocks(self):
        del self.receipt['compressed_sha256']
        with self.assertRaises(KeyError): self.validate()

    def test_wrong_compressed_hash_blocks(self):
        self.receipt['compressed_sha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'compressed binary hash'): self.validate()

    def test_upx_integrity_failure_blocks(self):
        self.receipt['statuses']['test'] = 1
        with self.assertRaisesRegex(ValueError, 'UPX'): self.validate()

    def test_decompression_failure_blocks(self):
        self.receipt['statuses']['decompress'] = 1
        with self.assertRaisesRegex(ValueError, 'decompression'): self.validate()

    def test_nondeterministic_repeat_failure_blocks(self):
        self.receipt['statuses']['repeat'] = 1
        with self.assertRaises(ValueError): self.validate()

    def test_missing_decompressed_hash_blocks(self):
        del self.receipt['decompressed_sha256']
        with self.assertRaises(KeyError): self.validate()

    def test_tampered_decompressed_payload_blocks(self):
        self.put('analysis/bin/ryuk', self.payload + b'tampered')
        with self.assertRaisesRegex(ValueError, 'Tampered decompressed'): self.validate()

    def test_empty_decompressed_payload_blocks(self):
        self.put('analysis/bin/ryuk', b'')
        with self.assertRaisesRegex(ValueError, 'ELF'): self.validate()

    def test_wrong_architecture_blocks(self):
        data = bytearray(self.payload)
        struct.pack_into('<H', data, 18, 183)
        self.put('analysis/bin/ryuk', data)
        with self.assertRaisesRegex(ValueError, 'amd64'): self.validate()

    def test_malformed_upx_identification_blocks(self):
        self.put('compressed.bin', self.compressed.replace(b'UPX!', b'BAD!'))
        with self.assertRaises(ValueError): self.validate()

    def test_missing_go_metadata_blocks(self):
        self.put('buildinfo.txt', b'no Go metadata\n')
        with self.assertRaisesRegex(ValueError, 'Go build metadata'): self.validate()

    def test_wrong_go_toolchain_blocks(self):
        self.put('buildinfo.txt', (self.directory / 'buildinfo.txt').read_bytes().replace(b'go1.23.12', b'go1.26.8'))
        with self.assertRaisesRegex(ValueError, 'Go toolchain'): self.validate()

    def test_missing_stdlib_readback_blocks(self):
        self.report['Results'][0]['Packages'].pop(1)
        with self.assertRaisesRegex(ValueError, 'stdlib'): self.validate()

    def test_missing_expected_module_blocks(self):
        self.report['Results'][0]['Packages'].pop()
        with self.assertRaisesRegex(ValueError, 'module identity'): self.validate()

    def test_unexpected_embedded_module_blocks(self):
        self.put('buildinfo.txt', (self.directory / 'buildinfo.txt').read_bytes().replace(b'v1.2.3', b'v2.0.0'))
        with self.assertRaises(ValueError): self.validate()

    def test_upstream_module_mismatch_blocks_even_when_receipt_is_rehashed(self):
        self.put('upstream/go.mod', ('module ' + self.module + '\nrequire (\n' + self.dependency + ' v2.0.0\n)\n').encode())
        self.transform['source_files']['go.mod'] = self.digest('upstream/go.mod')
        with self.assertRaisesRegex(ValueError, 'source/module mismatch'): self.validate()

    def test_upstream_checksum_mismatch_blocks(self):
        self.put('upstream/go.sum', (self.dependency + ' v1.2.3 h1:other\n').encode())
        self.transform['source_files']['go.sum'] = self.digest('upstream/go.sum')
        with self.assertRaisesRegex(ValueError, 'source/module mismatch'): self.validate()

    def test_high_and_critical_findings_remain_blocking(self):
        for severity in ('HIGH', 'CRITICAL'):
            with self.subTest(severity=severity):
                self.report['Results'][0]['Vulnerabilities'] = [{'VulnerabilityID': 'CVE-2026-1234',
                    'PkgName': 'stdlib', 'InstalledVersion': 'v1.23.12', 'Severity': severity}]
                self.assertEqual(severity, self.validate()['blocked'][0]['severity'])

    def test_scanner_and_database_failures_block(self):
        for field in ('scanner_status', 'database_status', 'sbom_status'):
            self.evidence[field] = 1
            with self.assertRaisesRegex(ValueError, 'failure'): self.validate()
            self.evidence[field] = 0

    def test_empty_scan_cannot_become_clean(self):
        self.report['Results'] = []
        with self.assertRaisesRegex(ValueError, 'package readback'): self.validate()

    def test_analysis_payload_cannot_be_reported_as_executed_image(self):
        self.report['ArtifactType'] = 'container_image'
        with self.assertRaisesRegex(ValueError, 'analysis representation'): self.validate()

    def test_executed_reference_cannot_be_replaced_by_analysis_payload(self):
        self.receipt['executed_image'] = '/analysis'
        with self.assertRaisesRegex(ValueError, 'execution image'): self.validate()

    def test_official_compressed_provenance_is_distinct_from_candidate_execution(self):
        contract = POLICY.read(POLICY.CONTRACT)
        image = contract['images']['ryuk']
        self.assertEqual('ryuk.container.image=' + image['reference'].removeprefix('docker.io/'),
                         (ROOT / 'src/test/resources/testcontainers.properties').read_text().splitlines()[0])
        self.assertNotEqual(image['reference'], contract['comparisons']['official_ryuk']['reference'])
        self.assertEqual('upx-deterministic-go-readback',
                         contract['comparisons']['official_ryuk']['evidence_transform']['method'])

    def test_no_ryuk_package_or_cve_exception(self):
        contract = POLICY.read(POLICY.CONTRACT)
        self.assertEqual([], contract['exceptions'])
        self.assertEqual([], contract['comparisons']['official_ryuk']['exceptions'])
        self.assertEqual(['HIGH', 'CRITICAL'], contract['comparisons']['official_ryuk']['threshold'])

    def test_normal_go_image_retains_direct_evidence_path(self):
        contract = POLICY.read(POLICY.CONTRACT)
        self.assertNotIn('evidence_transform', contract['images']['govulncheck'])
        self.assertNotIn('evidence_transform', contract['images']['scanner'])

    def test_binary_analysis_cannot_populate_nonroot_gosu_cache(self):
        source = (ROOT / 'scripts/scan-auxiliary-containers.py').read_text()
        self.assertIn("'ryuk-govulncheck:/tmp'", source)
        self.assertNotIn("'govulncheck:/tmp'", source)

    def test_fixture_cannot_enter_compressed_binary_trust_path(self):
        contract = POLICY.read(POLICY.CONTRACT)
        contract['images']['fixture']['evidence_transform'] = contract['comparisons']['official_ryuk']['evidence_transform']
        with self.assertRaisesRegex(ValueError, 'fixture compressed'): POLICY.inventory(contract)

    def test_decompressor_integrity_change_blocks(self):
        self.put('tools/upx', b'tampered')
        with self.assertRaisesRegex(ValueError, 'Decompressor integrity'): self.validate()

    def test_report_bytes_must_match_transformation_receipt(self):
        self.validate()
        self.put('raw.json', b'{}')
        with self.assertRaisesRegex(ValueError, 'report binding'):
            BINARY.validate(self.image, self.evidence, self.report, self.bom, self.directory)

    def test_binary_govulncheck_failure_is_not_zero_findings(self):
        self.receipt['govulncheck_status'] = 1
        with self.assertRaisesRegex(ValueError, 'govulncheck'): self.validate()

    def test_govulncheck_findings_cannot_exempt_package_threshold(self):
        self.receipt['govulncheck_status'] = 3
        self.report['Results'][0]['Vulnerabilities'] = [{'VulnerabilityID': 'CVE-2026-1234',
            'PkgName': 'stdlib', 'InstalledVersion': 'v1.23.12', 'Severity': 'HIGH'}]
        self.assertEqual(1, len(self.validate()['blocked']))

    def test_provenance_source_mismatch_blocks(self):
        statement = json.loads((self.directory / 'upstream-provenance.json').read_text())
        statement['predicate']['metadata']['https://mobyproject.org/buildkit@v1#metadata']['vcs']['revision'] = '0' * 40
        self.put_json('upstream-provenance.json', statement)
        self.transform['upstream_provenance']['statement_digest'] = 'sha256:' + self.digest('upstream-provenance.json')
        with self.assertRaisesRegex(ValueError, 'provenance/source mismatch'): self.validate()

    def provenance_responses(self):
        return [io.BytesIO(json.dumps({'token': 'offline-registry-token'}).encode()),
                io.BytesIO((self.directory / 'upstream-provenance-manifest.json').read_bytes()),
                io.BytesIO((self.directory / 'upstream-provenance.json').read_bytes())]

    def provenance_policy(self, reviewed=None):
        self.put_json('provenance-policy.json', {'comparisons': {'official_ryuk': reviewed or self.reviewed_image}})
        return patch.object(BINARY, 'PROVENANCE_POLICY', self.directory / 'provenance-policy.json')

    def assert_provenance_requests(self, network):
        self.assertEqual(3, network.call_count)
        token_url = urlsplit(network.call_args_list[0].args[0])
        self.assertEqual(('https', 'auth.docker.io', '/token', ''),
                         (token_url.scheme, token_url.netloc, token_url.path, token_url.fragment))
        self.assertEqual({'service': ['registry.docker.io'],
                          'scope': ['repository:testcontainers/ryuk:pull']}, parse_qs(token_url.query))
        authority = self.transform['upstream_provenance']
        for call, suffix in zip(network.call_args_list[1:],
                ('manifests/' + authority['manifest_digest'], 'blobs/' + authority['statement_digest'])):
            request = call.args[0]
            url = urlsplit(request.full_url)
            self.assertEqual(('https', 'registry-1.docker.io', '/v2/testcontainers/ryuk/' + suffix, '', ''),
                             (url.scheme, url.netloc, url.path, url.query, url.fragment))
            self.assertEqual('Bearer offline-registry-token', request.get_header('Authorization'))

    def test_reviewed_provenance_requests_keep_exact_https_hosts_scope_and_digests(self):
        with self.provenance_policy(), patch.object(BINARY.urllib.request, 'urlopen',
                side_effect=self.provenance_responses()) as network:
            BINARY.provenance(self.transform, self.directory, self.image)
            self.assert_provenance_requests(network)

    def test_actual_official_ryuk_identity_is_accepted(self):
        reviewed = POLICY.read(POLICY.CONTRACT)['comparisons']['official_ryuk']
        digest, authority = BINARY.provenance_identity(reviewed['evidence_transform'], reviewed)
        self.assertEqual(reviewed['reference'].split('@')[1], digest)
        self.assertEqual(reviewed['evidence_transform']['upstream_provenance'], authority)

    def test_future_official_version_requires_deliberate_policy_adoption(self):
        image = copy.deepcopy(self.image)
        image['version'] = '0.15.0'
        image['reference'] = image['reference'].replace(':0.14.0@', ':0.15.0@')
        with self.provenance_policy(), patch.object(BINARY.urllib.request, 'urlopen') as network:
            with self.assertRaisesRegex(ValueError, 'unreviewed version'):
                BINARY.provenance(self.transform, self.directory, image)
            network.assert_not_called()
        with self.provenance_policy(image), patch.object(BINARY.urllib.request, 'urlopen',
                side_effect=self.provenance_responses()) as network:
            BINARY.provenance(self.transform, self.directory, image)
            self.assert_provenance_requests(network)

    def test_misleading_registry_forms_fail_before_authentication(self):
        for reference in ('evil.example/docker.io/testcontainers/ryuk:0.14.0',
                          'docker.io.evil.example/testcontainers/ryuk:0.14.0',
                          'docker.io@evil.example/testcontainers/ryuk:0.14.0',
                          'https://docker.io/testcontainers/ryuk:0.14.0',
                          'https://docker.io@evil.example/testcontainers/ryuk:0.14.0',
                          '//docker.io/testcontainers/ryuk:0.14.0',
                          'ghcr.io/testcontainers/ryuk:0.14.0',
                          '\ndocker.io/testcontainers/ryuk:0.14.0'):
            with self.subTest(reference=reference), patch.object(BINARY.urllib.request, 'urlopen') as network:
                with self.assertRaisesRegex(ValueError, 'Official compressed comparison'):
                    BINARY.provenance(self.transform, self.directory, {'reference': reference})
                network.assert_not_called()

    def test_actual_comparison_census_rejects_repository_tag_and_delimiter_changes(self):
        reviewed = POLICY.read(POLICY.CONTRACT)
        suffix = reviewed['comparisons']['official_ryuk']['reference'].split('@', 1)[1]
        for prefix in ('docker.io/evil/ryuk:0.14.0', 'docker.io/testcontainers/ryuk:latest',
                       'docker.io/user:password@testcontainers/ryuk:0.14.0',
                       'docker.io/testcontainers/../evil:0.14.0',
                       'docker.io/testcontainers//ryuk:0.14.0',
                       'docker.io/testcontainers/%2e%2e/evil:0.14.0',
                       'docker.io/testcontainers/ryuk?scope=repository:evil:pull',
                       'docker.io/testcontainers/ryuk&scope=repository:evil:pull',
                       'docker.io/testcontainers/ryuk#fragment:0.14.0',
                       'docker.io/testcontainers/ryuk%26scope=evil:0.14.0',
                       'docker.io/testcontainers/ryuk\\evil:0.14.0'):
            contract = copy.deepcopy(reviewed)
            contract['comparisons']['official_ryuk']['reference'] = prefix + '@' + suffix
            with self.subTest(prefix=prefix), patch.object(BINARY.urllib.request, 'urlopen') as network:
                with self.assertRaisesRegex(ValueError, 'Official compressed comparison'):
                    POLICY.inventory(contract)
                network.assert_not_called()

    def test_suffix_delimiters_fail_before_network_access(self):
        for suffix in ('?scope=repository:evil:pull', '&scope=repository:evil:pull', '#fragment',
                       '/../../evil', '@evil.example', '%2f..%2f', '\\evil', '\n'):
            image = copy.deepcopy(self.image)
            image['reference'] += suffix
            with self.subTest(suffix=suffix), patch.object(BINARY.urllib.request, 'urlopen') as network:
                with self.assertRaisesRegex(ValueError, 'Official compressed comparison'):
                    BINARY.provenance(self.transform, self.directory, image)
                network.assert_not_called()

    def test_direct_helper_rejects_repository_version_credentials_encoding_and_controls(self):
        digest = self.image['reference'].split('@')[1]
        for prefix in ('docker.io/evil/ryuk:0.14.0', 'docker.io/testcontainers/evil:0.14.0',
                       'docker.io/testcontainers/ryuk:latest', 'docker.io/testcontainers/ryuk:0.15.0',
                       'docker.io/user:password@testcontainers/ryuk:0.14.0',
                       'docker.io/testcontainers/../evil:0.14.0', 'docker.io/testcontainers//ryuk:0.14.0',
                       'docker.io/testcontainers/%2e%2e/ryuk:0.14.0',
                       'docker.io/testcontainers/ryuk?scope=repository:evil:pull',
                       'docker.io/testcontainers/ryuk&scope=repository:evil:pull',
                       'docker.io/testcontainers/ryuk#fragment:0.14.0',
                       'docker.io/testcontainers/ryuk%26scope=evil:0.14.0',
                       'docker.io/testcontainers%2fryuk:0.14.0', 'docker.io/testcontainers/ryuk:%30.14.0',
                       'docker.io/testcontainers/ryuk\\evil:0.14.0', 'docker.io/testcontainers/ryuk\x00:0.14.0',
                       'docker.io/testcontainers/ryuk\r\n:0.14.0', 'docker.io/testcontainers/ryuk\t:0.14.0'):
            with self.subTest(prefix=prefix), self.provenance_policy(), patch.object(BINARY.urllib.request, 'urlopen') as network:
                with self.assertRaisesRegex(ValueError, 'Official compressed comparison'):
                    BINARY.provenance(self.transform, self.directory, {'reference': prefix + '@' + digest})
                network.assert_not_called()

    def test_direct_helper_rejects_missing_malformed_and_unreviewed_image_digests(self):
        for tail in ('', 'sha256:', 'sha256:' + '0' * 63, 'sha256:' + '0' * 65,
                     'sha256:' + 'G' * 64, 'sha256:' + 'A' * 64, 'sha256:' + '0' * 64,
                     'sha512:' + '0' * 64, 'sha256:%30' + '0' * 63):
            image = {'reference': 'docker.io/testcontainers/ryuk:0.14.0@' + tail}
            with self.subTest(tail=tail), self.provenance_policy(), patch.object(BINARY.urllib.request, 'urlopen') as network:
                with self.assertRaisesRegex(ValueError, 'Official compressed comparison'):
                    BINARY.provenance(self.transform, self.directory, image)
                network.assert_not_called()
        for reference in (None, '', 'docker.io/testcontainers/ryuk:0.14.0'):
            with self.subTest(reference=reference), patch.object(BINARY.urllib.request, 'urlopen') as network:
                with self.assertRaises(ValueError):
                    BINARY.provenance(self.transform, self.directory, {'reference': reference})
                network.assert_not_called()

    def test_unreviewed_provenance_identity_fails_before_authentication(self):
        for path in (('resolved_digest',), ('source_commit',),
                     ('upstream_provenance', 'manifest_digest'), ('upstream_provenance', 'statement_digest'),
                     ('upstream_provenance', 'repository'), ('upstream_provenance', 'builder')):
            spec = copy.deepcopy(self.transform)
            target = spec if len(path) == 1 else spec[path[0]]
            target[path[-1]] = 'sha256:' + '0' * 64 + '?scope=evil'
            with self.subTest(path=path), self.provenance_policy(), patch.object(BINARY.urllib.request, 'urlopen') as network:
                with self.assertRaisesRegex(ValueError, 'unreviewed provenance identity'):
                    BINARY.provenance(spec, self.directory, self.image)
                network.assert_not_called()

    def test_malformed_policy_provenance_digests_cannot_be_used_as_request_paths(self):
        for key in ('manifest_digest', 'statement_digest'):
            for value in ('sha256:', 'sha256:' + '0' * 63, 'sha256:' + '0' * 64 + '?scope=evil',
                          'sha256:' + '0' * 64 + '#fragment', '../evil', 'sha256:%2f..%2f', None):
                image = copy.deepcopy(self.image)
                image['evidence_transform']['upstream_provenance'][key] = value
                with self.subTest(key=key, value=value), self.provenance_policy(image), patch.object(BINARY.urllib.request, 'urlopen') as network:
                    with self.assertRaisesRegex(ValueError, 'malformed provenance digest'):
                        BINARY.provenance(image['evidence_transform'], self.directory, image)
                    network.assert_not_called()

    def test_changed_index_bytes_fail_before_authentication(self):
        self.put('registry-index.raw.json', b'{"manifests":[]}')
        with self.provenance_policy(), patch.object(BINARY.urllib.request, 'urlopen') as network:
            with self.assertRaisesRegex(ValueError, 'image index digest mismatch'):
                BINARY.provenance(self.transform, self.directory, self.image)
            network.assert_not_called()

    def test_changed_or_malformed_image_digest_fails_measured_identity(self):
        reference = self.image['reference']
        for tail in ('0' * 64, reference.split(':')[-1] + '?scope=evil',
                     reference.split(':')[-1] + '#fragment', reference.split(':')[-1] + '/..'):
            self.image['reference'] = 'docker.io/testcontainers/ryuk:0.14.0@sha256:' + tail
            self.evidence['reference'] = self.image['reference']
            self.receipt['executed_image'] = self.image['reference']
            with self.subTest(tail=tail), self.assertRaisesRegex(ValueError, 'registry index/platform manifest'):
                self.validate()

    def test_provenance_response_digest_mismatch_fails_before_statement_request(self):
        responses = [io.BytesIO(b'{"token":"offline-registry-token"}'), io.BytesIO(b'{"layers":[]}')]
        with self.provenance_policy(), patch.object(BINARY.urllib.request, 'urlopen', side_effect=responses) as network:
            with self.assertRaisesRegex(ValueError, 'Upstream provenance digest mismatch'):
                BINARY.provenance(self.transform, self.directory, self.image)
            self.assertEqual(2, network.call_count)

    def test_statement_byte_drift_is_rejected(self):
        responses = self.provenance_responses()
        responses[2] = io.BytesIO(b'{"subject":[]}')
        with self.provenance_policy(), patch.object(BINARY.urllib.request, 'urlopen', side_effect=responses) as network:
            with self.assertRaisesRegex(ValueError, 'Upstream provenance digest mismatch'):
                BINARY.provenance(self.transform, self.directory, self.image)
            self.assertEqual(3, network.call_count)

    def test_digest_bound_response_with_wrong_image_subject_is_rejected(self):
        statement = json.loads((self.directory / 'upstream-provenance.json').read_bytes())
        statement['subject'][0]['digest']['sha256'] = '0' * 64
        self.put_json('upstream-provenance.json', statement)
        upstream = self.transform['upstream_provenance']
        upstream['statement_digest'] = 'sha256:' + self.digest('upstream-provenance.json')
        self.put_json('upstream-provenance-manifest.json', {'layers': [{'digest': upstream['statement_digest']}]})
        upstream['manifest_digest'] = 'sha256:' + self.digest('upstream-provenance-manifest.json')
        self.put_json('registry-index.raw.json', {'manifests': [{'digest': upstream['manifest_digest']}]})
        self.image['reference'] = 'docker.io/testcontainers/ryuk:0.14.0@sha256:' + self.digest('registry-index.raw.json')
        with self.provenance_policy(self.image), patch.object(BINARY.urllib.request, 'urlopen',
                side_effect=self.provenance_responses()) as network:
            with self.assertRaisesRegex(ValueError, 'Wrong upstream image provenance subject'):
                BINARY.provenance(self.transform, self.directory, self.image)
            self.assertEqual(3, network.call_count)

    def test_isolated_helper_and_real_caller_reject_original_scope_injection(self):
        reference = 'docker.io/testcontainers/ryuk&scope=repository:evil:pull@sha256:' + '0' * 64
        with patch.object(BINARY.urllib.request, 'urlopen') as network:
            with self.assertRaisesRegex(ValueError, 'Official compressed comparison'):
                BINARY.provenance(self.transform, self.directory, {'reference': reference})
            network.assert_not_called()
        contract = POLICY.read(POLICY.CONTRACT)
        contract['comparisons']['official_ryuk']['reference'] = reference
        with patch.object(BINARY.urllib.request, 'urlopen') as network:
            with self.assertRaisesRegex(ValueError, 'Official compressed comparison'):
                POLICY.inventory(contract)
            network.assert_not_called()


if __name__ == '__main__':
    unittest.main()
