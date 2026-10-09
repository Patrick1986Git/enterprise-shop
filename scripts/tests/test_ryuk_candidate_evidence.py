"""Offline mutations of the candidate's identity and fail-closed advisory contract."""
import copy
import importlib.util
import json
import struct
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).parents[2]
SPEC = importlib.util.spec_from_file_location('policy', ROOT / 'scripts/validate-auxiliary-containers.py')
POLICY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(POLICY)
CANDIDATE = POLICY.CANDIDATE
BUILD_SPEC = importlib.util.spec_from_file_location('build', ROOT / 'scripts/build-ryuk-candidate.py')
BUILD = importlib.util.module_from_spec(BUILD_SPEC)
BUILD_SPEC.loader.exec_module(BUILD)


class CandidateEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.candidate = json.loads(CANDIDATE.CONTRACT.read_text())
        self.image = copy.deepcopy(POLICY.read(POLICY.CONTRACT)['images']['ryuk'])
        header = bytearray(64)
        header[:7] = b'\x7fELF\x02\x01\x01'
        struct.pack_into('<HH', header, 16, 2, 62)
        binary = bytes(header) + b'candidate fixture'
        reference, image_id, config, layer, archive = BUILD.image_archive(binary, b'ca fixture')
        self.candidate.update({'image_reference': reference, 'image_id': image_id,
            'binary_sha256': CANDIDATE.sha(binary), 'repeat_sha256': CANDIDATE.sha(binary),
            'archive_sha256': CANDIDATE.sha(archive), 'layer_sha256': CANDIDATE.sha(layer),
            'upstream_archive_sha256': CANDIDATE.sha(b'archive fixture'),
            'filesystem': {'bin/ryuk': {'sha256': CANDIDATE.sha(binary), 'size': len(binary)},
                          'etc/ssl/certs/ca-certificates.crt': {'sha256': CANDIDATE.sha(b'ca fixture'), 'size': 10}}})
        self.image['reference'] = reference
        self.image['identity']['sha256'] = self.candidate['binary_sha256']
        self.put('ryuk', binary)
        self.put('ryuk.repeat', binary)
        self.put('image.tar', archive)
        self.put('image-config.json', config)
        self.put('upstream.tar.gz', b'archive fixture')
        metadata = self.candidate['buildinfo']
        text = '/evidence/ryuk: go1.26.9\n\tpath\t' + metadata['path'] + '\n\tmod\t' + metadata['main_module'] + '\t(devel)\t\n'
        for name, dep in metadata['dependencies'].items():
            text += '\tdep\t' + name + '\t' + dep['version'] + '\t' + dep['sum'] + '\n'
        for key, value in metadata['build'].items():
            text += '\tbuild\t' + key + '=' + value + '\n'
        self.put('buildinfo.txt', text.encode())
        mod = 'module ' + metadata['main_module'] + '\nrequire (\n'
        sums = ''
        modules, packages = [], [{'ImportPath': metadata['main_module'], 'Name': 'main'}]
        for name, dep in metadata['dependencies'].items():
            mod += '\t' + name + ' ' + dep['version'] + '\n'
            sums += name + ' ' + dep['version'] + ' ' + dep['sum'] + '\n'
            modules.append({'path': name, 'version': dep['version']})
            packages.append({'ImportPath': name + '/fixture', 'Module': {
                'Path': name, 'Version': dep['version'], 'Sum': dep['sum']}})
        mod += ')\n'
        self.put('source/go.mod', mod.encode())
        self.put('source/go.sum', sums.encode())
        self.candidate['source_files'] = {name: CANDIDATE.sha((self.directory / 'source' / name).read_bytes())
                                         for name in ['go.mod', 'go.sum']}
        self.put('source-packages.json', '\n'.join(json.dumps(p) for p in packages).encode())
        self.put('source-modules.json', json.dumps(modules).encode())
        self.put('govulncheck', b'tool fixture')
        self.put('govulncheck-buildinfo.txt', ('/evidence/govulncheck: go1.26.9\n'
            '\tpath\tgolang.org/x/vuln/cmd/govulncheck\n\tmod\tgolang.org/x/vuln\tv1.8.0\t'
            + self.candidate['govulncheck_sum'] + '\n').encode())
        self.receipt = {k: v for k, v in self.candidate.items() if k not in ['proof', 'source_authority', 'applicability', 'buildinfo']}
        self.receipt['repository_head'] = 'head fixture'
        self.receipt['tool_sha256'] = CANDIDATE.sha(b'tool fixture')
        self.receipt['evidence_hashes'] = {name: CANDIDATE.sha((self.directory / name).read_bytes())
            for name in ['buildinfo.txt', 'source-packages.json', 'source-modules.json', 'govulncheck-buildinfo.txt']}
        self.receipt['analysis'] = {}
        self.put('tests/fixture_test.go', b'test fixture')
        self.put('client-tests.jsonl', b'{"Action":"pass","Test":"TestReview"}\n')
        self.test_contract = self.directory / 'client-contract.json'
        files = {'fixture_test.go': CANDIDATE.sha(b'test fixture')}
        self.put(self.test_contract.name, json.dumps({'source_files': files, 'passed_tests': ['TestReview']}).encode())
        self.receipt['client_tests'] = {'status': 0, 'source_files': files,
            'output_sha256': CANDIDATE.sha((self.directory / 'client-tests.jsonl').read_bytes())}
        for mode in ['source', 'binary']:
            stream = [{'config': {'scan_mode': mode, 'scan_level': 'symbol', 'scanner_name': 'govulncheck',
                       'scanner_version': 'v1.8.0', 'db': 'https://vuln.go.dev',
                       'db_last_modified': datetime.now(timezone.utc).isoformat()}},
                      {'SBOM': {'go_version': 'go1.26.9', 'roots': [metadata['main_module']], 'modules': modules}}]
            self.put(mode + '-govulncheck.json', '\n'.join(json.dumps(m) for m in stream).encode())
            self.receipt['analysis'][mode] = {'status': 0, 'sha256': CANDIDATE.sha((self.directory / (mode + '-govulncheck.json')).read_bytes())}
        self.save()
        now = datetime.now(timezone.utc)
        self.evidence = {'reference': reference, 'platform': 'linux/amd64', 'image_id': image_id,
            'resolved_digest': image_id, 'identity': self.image['identity'], 'identity_authority': 'local-configuration-and-archive',
            'database_status': 0, 'scanner_status': 0, 'sbom_status': 0, 'independent_scanner_integrity': False,
            'timestamp': now.isoformat(), 'database': {'Version': 2, 'UpdatedAt': (now - timedelta(hours=1)).isoformat(),
                'NextUpdate': (now + timedelta(hours=2)).isoformat()}, 'installed_os_packages': {}, 'artifact_name': '/input/ryuk.tar'}
        packages = [{'Name': n, 'Version': d['version'], 'Identifier': {'PURL': 'pkg:golang/' + n + '@' + d['version']}}
                    for n, d in metadata['dependencies'].items()]
        packages += [{'Name': 'stdlib', 'Version': 'v1.26.9', 'Identifier': {'PURL': 'pkg:golang/stdlib@v1.26.9'}},
                     {'Name': metadata['main_module'], 'Relationship': 'root', 'Identifier': {'PURL': 'pkg:golang/' + metadata['main_module']}}]
        self.report = {'SchemaVersion': 2, 'ArtifactType': 'container_image', 'ArtifactName': '/input/ryuk.tar',
            'Metadata': {'ImageID': image_id}, 'Trivy': {'Version': '0.75.0'}, 'Results': [
                {'Target': 'bin/ryuk', 'Type': 'gobinary', 'Class': 'lang-pkgs', 'Packages': packages}]}
        self.bom = {'bomFormat': 'CycloneDX', 'specVersion': '1.7', 'metadata': {'component': {
            'name': '/input/ryuk.tar', 'properties': [{'name': 'aquasecurity:trivy:ImageID', 'value': image_id}]}},
            'components': [{'purl': p['Identifier']['PURL']} for p in packages]}

    def put(self, name, data):
        path = self.directory / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)

    def save(self):
        self.put('build-receipt.json', json.dumps(self.receipt).encode())
        self.put('candidate.json', json.dumps(self.candidate).encode())

    def validate(self):
        self.save()
        with patch.object(CANDIDATE, 'CONTRACT', self.directory / 'candidate.json'), \
                patch.object(CANDIDATE.CLIENT_TESTS, 'CONTRACT', self.test_contract), \
                patch.object(POLICY.subprocess, 'check_output', return_value='head fixture'):
            return POLICY.validate(self.image, self.evidence, self.report, self.bom, '0.75.0', self.directory)

    def test_patched_candidate_clears_standard_library_gate(self):
        self.assertEqual([], self.validate()['blocked'])

    def test_vulnerable_go_standard_library_cannot_be_a_clean_candidate(self):
        self.put('buildinfo.txt', (self.directory / 'buildinfo.txt').read_bytes().replace(b'go1.26.9', b'go1.23.12'))
        with self.assertRaises(ValueError): self.validate()

    def test_previous_go_1_26_8_candidate_cannot_reauthorize_itself(self):
        self.candidate['go_version'] = self.image['go_version'] = '1.26.8'
        self.candidate['buildinfo']['go_version'] = '1.26.8'
        with self.assertRaisesRegex(ValueError, 'Wrong candidate identity'): self.validate()

    def test_all_new_advisories_block_without_a_reachable_symbol(self):
        before = json.loads((ROOT / '.github/security/ryuk/go-1.26.8-advisories.json').read_text())
        self.assertEqual((13, 12), (before['analysis']['source']['distinct'], before['analysis']['binary']['distinct']))
        for mode in ['source', 'binary']:
            path = self.directory / (mode + '-govulncheck.json')
            clean = path.read_bytes()
            for advisory in before['advisories']:
                if mode not in advisory['finding_examples']:
                    continue
                with self.subTest(mode=mode, advisory=advisory['id']):
                    finding = copy.deepcopy(advisory['finding_examples'][mode])
                    # Package-level findings must block even without a function trace.
                    finding['trace'] = [{'module': 'stdlib', 'version': 'v1.26.8'}]
                    self.put(path.name, clean + b'\n' + json.dumps({'finding': finding}).encode())
                    self.receipt['analysis'][mode]['sha256'] = CANDIDATE.sha(path.read_bytes())
                    with self.assertRaisesRegex(ValueError, 'advisory findings'): self.validate()
            self.put(path.name, clean)
            self.receipt['analysis'][mode]['sha256'] = CANDIDATE.sha(clean)

    def test_wrong_builder_digest_fails_closed(self):
        self.receipt['builder'] = self.receipt['builder'].split('@')[0] + '@sha256:' + 'a' * 64
        with self.assertRaisesRegex(ValueError, 'identity drift'): self.validate()

    def test_changed_module_checksum_fails_even_with_updated_source_receipts(self):
        path = self.directory / 'source/go.sum'
        self.put('source/go.sum', path.read_bytes().replace(b'h1:', b'h2:', 1))
        self.candidate['source_files']['go.sum'] = CANDIDATE.sha(path.read_bytes())
        self.receipt['source_files']['go.sum'] = self.candidate['source_files']['go.sum']
        with self.assertRaisesRegex(ValueError, 'source/module mismatch'): self.validate()

    def test_unrecognized_candidate_image_fails_closed(self):
        self.image['reference'] = 'local/unreviewed-ryuk:latest'
        with self.assertRaises(ValueError): self.validate()

    def test_stale_go_advisory_evidence_fails_closed(self):
        for mode in ['source', 'binary']:
            path = self.directory / (mode + '-govulncheck.json')
            stream = CANDIDATE.messages(path.read_bytes())
            stream[0]['config']['db_last_modified'] = (datetime.now(timezone.utc) - timedelta(days=3)).isoformat()
            self.put(path.name, '\n'.join(json.dumps(m) for m in stream).encode())
            self.receipt['analysis'][mode]['sha256'] = CANDIDATE.sha(path.read_bytes())
            with self.assertRaisesRegex(ValueError, 'Stale Go advisory'): self.validate()

    def test_standard_library_high_or_critical_is_always_blocking(self):
        before = json.loads((ROOT / '.github/security/ryuk/official-0.14.0-advisories.json').read_text())
        for finding in before['advisories']:
            if finding['PkgName'] == 'stdlib' and finding['Severity'] in {'HIGH', 'CRITICAL'}:
                self.report['Results'][0]['Vulnerabilities'] = [{'VulnerabilityID': finding['VulnerabilityID'],
                    'Severity': finding['Severity'], 'PkgName': 'stdlib', 'InstalledVersion': 'v1.26.9'}]
                self.assertEqual(finding['VulnerabilityID'], self.validate()['blocked'][0]['id'])

    def test_source_binary_module_version_mismatch_fails(self):
        self.put('source/go.mod', (self.directory / 'source/go.mod').read_bytes().replace(b'v0.6.1', b'v0.6.0'))
        with self.assertRaises(ValueError): self.validate()

    def test_docker_high_is_blocking_without_any_decision(self):
        for cve in ['CVE-2026-41567', 'CVE-2026-42306']:
            self.report['Results'][0]['Vulnerabilities'] = [{'VulnerabilityID': cve, 'Severity': 'HIGH',
                'PkgName': 'github.com/moby/moby/client', 'InstalledVersion': 'v0.6.1'}]
            self.assertEqual(cve, self.validate()['blocked'][0]['id'])

    def test_one_cve_decision_cannot_suppress_another(self):
        self.image['applicability'] = [{'id': 'CVE-2026-41567'}]
        with self.assertRaisesRegex(ValueError, 'applicability'): self.validate()

    def test_ryuk_decision_cannot_leak_to_another_image(self):
        image = copy.deepcopy(POLICY.read(POLICY.CONTRACT)['images']['scanner'])
        image['applicability'] = [{'id': 'CVE-2026-41567', 'image': self.image['reference']}]
        with self.assertRaisesRegex(ValueError, 'applicability'):
            POLICY.validate(image, {}, {}, {}, '0.75.0')

    def test_source_sha_drift_invalidates_evidence(self):
        self.receipt['upstream_source_sha'] = 'a' * 40
        with self.assertRaises(ValueError): self.validate()

    def test_binary_sha_drift_invalidates_evidence(self):
        self.put('ryuk', (self.directory / 'ryuk').read_bytes() + b'drift')
        with self.assertRaises(ValueError): self.validate()

    def test_image_config_drift_invalidates_evidence(self):
        self.put('image-config.json', b'{}')
        with self.assertRaises(ValueError): self.validate()

    def test_module_version_drift_invalidates_evidence(self):
        self.receipt['source_files']['go.mod'] = 'a' * 64
        with self.assertRaises(ValueError): self.validate()

    def test_reachable_vulnerable_function_invalidates_evidence(self):
        path = self.directory / 'source-govulncheck.json'
        self.put(path.name, path.read_bytes() + b'\n{"finding":{"osv":"GO-2026-5746","trace":[{"function":"containerExtractToDir"}]}}')
        self.receipt['analysis']['source']['sha256'] = CANDIDATE.sha(path.read_bytes())
        with self.assertRaisesRegex(ValueError, 'advisory findings'): self.validate()

    def test_daemon_package_in_source_graph_invalidates_evidence(self):
        path = self.directory / 'source-packages.json'
        self.put(path.name, path.read_bytes() + b'\n{"ImportPath":"github.com/moby/moby/v2/daemon"}')
        self.receipt['evidence_hashes'][path.name] = CANDIDATE.sha(path.read_bytes())
        with self.assertRaisesRegex(ValueError, 'reachable'): self.validate()

    def test_missing_source_mode_evidence_invalidates_evidence(self):
        (self.directory / 'source-govulncheck.json').unlink()
        with self.assertRaises(OSError): self.validate()

    def test_missing_binary_mode_evidence_invalidates_evidence(self):
        (self.directory / 'binary-govulncheck.json').unlink()
        with self.assertRaises(OSError): self.validate()

    def test_expired_applicability_cannot_be_accepted(self):
        self.image['applicability'] = [{'id': 'CVE-2026-41567', 'expires': '2000-01-01'}]
        with self.assertRaisesRegex(ValueError, 'applicability'): self.validate()

    def test_stale_trivy_database_still_blocks_candidate(self):
        self.evidence['database']['NextUpdate'] = (datetime.now(timezone.utc) - timedelta(seconds=1)).isoformat()
        with self.assertRaisesRegex(ValueError, 'stale database'): self.validate()

    def test_wrong_execution_image_id_blocks_candidate(self):
        self.evidence['image_id'] = 'sha256:' + 'a' * 64
        with self.assertRaises(ValueError): self.validate()

    def test_analysis_failure_cannot_be_clean(self):
        self.receipt['analysis']['source']['status'] = 1
        with self.assertRaisesRegex(ValueError, 'failed source'): self.validate()

    def test_missing_failed_or_skipped_client_tests_block(self):
        for events in [[], [{'Action': 'fail', 'Test': 'TestReview'}], [{'Action': 'skip', 'Test': 'TestReview'}]]:
            self.put('client-tests.jsonl', '\n'.join(json.dumps(event) for event in events).encode())
            self.receipt['client_tests']['output_sha256'] = CANDIDATE.sha((self.directory / 'client-tests.jsonl').read_bytes())
            with self.assertRaisesRegex(ValueError, 'Ryuk client contract tests'): self.validate()

    def test_client_test_source_or_output_drift_blocks(self):
        self.put('tests/fixture_test.go', b'changed test')
        with self.assertRaisesRegex(ValueError, 'client test source'): self.validate()
        self.put('tests/fixture_test.go', b'test fixture')
        self.put('client-tests.jsonl', b'{}')
        with self.assertRaises((ValueError, KeyError)): self.validate()

    def test_legacy_daemon_advisory_provenance_is_preserved(self):
        before = json.loads((ROOT / '.github/security/ryuk/official-0.14.0-govulncheck-advisories.json').read_text())
        for cve, symbol in [('CVE-2026-41567', 'Daemon.containerExtractToDir'), ('CVE-2026-42306', 'Daemon.openContainerFS')]:
            advisory = next(a for a in before['advisories'] if cve in a['aliases'])
            legacy = next(a for a in advisory['affected'] if a['package']['name'] == 'github.com/docker/docker')
            self.assertEqual([{'type': 'SEMVER', 'events': [{'introduced': '0'}]}], legacy['ranges'])
            self.assertEqual([{'path': 'github.com/docker/docker/daemon', 'symbols': [symbol]}],
                             legacy['ecosystem_specific']['imports'])


if __name__ == '__main__':
    unittest.main()
