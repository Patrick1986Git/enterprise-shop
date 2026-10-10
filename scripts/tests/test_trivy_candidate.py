import importlib.util
import copy
import io
import json
import tarfile
import tempfile
import unittest
from pathlib import Path
from datetime import datetime, timedelta, timezone

ROOT = Path(__file__).parents[2]
SPEC = importlib.util.spec_from_file_location('trivy_builder', ROOT / 'scripts/build-trivy-candidate.py')
BUILD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUILD)
SPEC = importlib.util.spec_from_file_location('trivy_compatibility', ROOT / 'scripts/verify-trivy-candidate-compatibility.py')
COMPAT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(COMPAT)
SPEC = importlib.util.spec_from_file_location('trivy_evidence', ROOT / 'scripts/trivy_candidate_evidence.py')
EVIDENCE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(EVIDENCE)


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
            self.assertEqual({'usr/local/bin/trivy', 'etc/ssl/certs/ca-certificates.crt', 'contrib/test.tpl', 'tmp'}, set(census))
            self.assertEqual({'type': 'directory', 'mode': 0o1777}, census['tmp'])
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

    def test_auxiliary_copy_retains_complete_upstream_fixture_link_bytes(self):
        spec = importlib.util.spec_from_file_location('scan_auxiliary', ROOT / 'scripts/scan-auxiliary-containers.py')
        scan = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(scan)
        with tempfile.TemporaryDirectory() as directory:
            source, target = Path(directory) / 'source', Path(directory) / 'target'
            source.mkdir()
            (source / 'dangling-fixture').symlink_to('absent')
            (source / 'file').write_text('upstream bytes')
            (source / 'linked-fixture').symlink_to('file')
            expected = BUILD.source_files(source)
            scan.copy_candidate_evidence(source, target)
            self.assertEqual(expected, BUILD.source_files(target))
            self.assertTrue((target / 'dangling-fixture').is_symlink())

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

    def test_selected_scanner_requires_independent_build_authority(self):
        contract = json.loads((ROOT / '.github/security/auxiliary-container-scope.json').read_text())
        self.assertEqual(EVIDENCE.METHOD, contract['scanner_independent_integrity'])
        self.assertTrue(contract['images']['scanner']['governed'])
        self.assertTrue(contract['images']['scanner']['candidate_build'])
        builder = contract['images']['scanner-builder']
        self.assertEqual(BUILD.BUILDER, builder['reference'])
        self.assertEqual(BUILD.BUILDER_BINARY, builder['identity']['sha256'])
        self.assertTrue(builder['governed'])
        self.assertEqual(['HIGH', 'CRITICAL'], builder['threshold'])


class TrivyTrustContractTest(unittest.TestCase):
    def setUp(self):
        self.candidate = {'image_id': 'sha256:' + 'a' * 64,
            'image_reference': 'local/enterprise-shop-trivy:sha256-' + 'a' * 64,
            'binary_sha256': 'b' * 64, 'repeat_sha256': 'b' * 64, 'go_version': '1.27.2',
            'exceptions': [], 'applicability': [], 'builder': BUILD.BUILDER,
            'builder_image_id': BUILD.BUILDER_ID, 'builder_manifest': BUILD.BUILDER_MANIFEST,
            'builder_binary_sha256': BUILD.BUILDER_BINARY, 'sdk_sha256': BUILD.SDK_SHA, 'sdk_tools': BUILD.SDK_TOOLS,
            'upstream_source_sha': BUILD.SOURCE, 'upstream_archive_sha256': BUILD.ARCHIVE_SHA,
            'patch_sha256': BUILD.PATCH_SHA, 'go_mod_sha256': BUILD.GO_MOD_SHA, 'go_sum_sha256': BUILD.GO_SUM_SHA,
            'flags': BUILD.FLAGS, 'upx': False, 'independent_cold_caches': True,
            'buildinfo': {'go_version': '1.27.2', 'path': 'github.com/aquasecurity/trivy/cmd/trivy',
                'main_module': 'github.com/aquasecurity/trivy', 'main_version': '(devel)',
                'dependencies': {'golang.org/x/net': {'version': 'v0.60.0',
                    'sum': 'h1:79p50tfZlm0J9YfoDsSi639qSXNGVwEzOPLCxM2FsYU='}},
                'build': {'CGO_ENABLED': '0', 'GOARCH': 'amd64', 'GOOS': 'linux', 'GOAMD64': 'v1',
                          '-buildmode': 'exe', '-trimpath': 'true'}}}
        self.image = {'reference': self.candidate['image_reference'], 'go_version': '1.27.2',
                      'identity': {'sha256': self.candidate['binary_sha256']}, 'exceptions': [], 'applicability': []}

    def validate(self):
        EVIDENCE.inventory(self.image, self.candidate)

    def test_reviewed_source_compiler_and_module_identities_are_accepted(self):
        self.validate()

    def test_known_vulnerable_official_executable_cannot_be_selected(self):
        self.candidate['binary_sha256'] = self.candidate['repeat_sha256'] = COMPAT.REFERENCE_BINARY
        self.image['identity']['sha256'] = COMPAT.REFERENCE_BINARY
        with self.assertRaisesRegex(ValueError, 'vulnerable'):
            self.validate()

    def test_vulnerable_compiler_or_net_module_is_rejected(self):
        for mutate in (lambda v: v['buildinfo'].update(go_version='1.27.1'),
                       lambda v: v['buildinfo']['dependencies']['golang.org/x/net'].update(version='v0.59.0')):
            old = copy.deepcopy(self.candidate)
            mutate(self.candidate)
            with self.assertRaisesRegex(ValueError, 'vulnerable'):
                self.validate()
            self.candidate = old

    def test_source_patch_and_go_manifests_cannot_drift(self):
        for key in ('upstream_source_sha', 'upstream_archive_sha256', 'patch_sha256', 'go_mod_sha256', 'go_sum_sha256'):
            with self.subTest(key=key):
                old = self.candidate[key]
                self.candidate[key] = 'changed'
                with self.assertRaisesRegex(ValueError, 'source'):
                    self.validate()
                self.candidate[key] = old

    def test_unexpected_builder_or_compiler_bytes_are_rejected(self):
        for key in ('builder', 'builder_image_id', 'builder_manifest', 'builder_binary_sha256', 'sdk_sha256'):
            with self.subTest(key=key):
                old = self.candidate[key]
                self.candidate[key] = 'changed'
                with self.assertRaisesRegex(ValueError, 'compiler'):
                    self.validate()
                self.candidate[key] = old

    def test_nonreproducible_executable_is_rejected(self):
        self.candidate['repeat_sha256'] = 'c' * 64
        with self.assertRaisesRegex(ValueError, 'nonreproducible'):
            self.validate()

    def test_same_compiler_cache_cannot_be_reported_as_independent_builds(self):
        self.candidate['independent_cold_caches'] = False
        with self.assertRaisesRegex(ValueError, 'settings'):
            self.validate()

    def test_legacy_docker_module_in_actual_binary_cannot_be_waived(self):
        self.candidate['buildinfo']['dependencies']['github.com/docker/docker'] = {'version': 'v28.5.2+incompatible', 'sum': 'h1:fixture'}
        with self.assertRaisesRegex(ValueError, 'Legacy Docker'):
            self.validate()

    def test_scanner_advisory_exceptions_are_rejected(self):
        self.candidate['exceptions'] = ['CVE-2026-78669']
        with self.assertRaisesRegex(ValueError, 'exception'):
            self.validate()

    def test_scanner_applicability_waiver_is_rejected(self):
        self.candidate['applicability'] = ['GO-2026-5932']
        with self.assertRaisesRegex(ValueError, 'waiver'):
            self.validate()


class IndependentTrivyAnalysisTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.metadata = {'path': 'github.com/aquasecurity/trivy/cmd/trivy',
                         'main_module': 'github.com/aquasecurity/trivy',
                         'dependencies': {'golang.org/x/net': {'version': 'v0.60.0'}}}
        self.packages = [{'ImportPath': 'net/http'}, {'ImportPath': 'golang.org/x/net/http2',
                         'Module': {'Path': 'golang.org/x/net'}}]
        functions = sorted(['net/http.F' + str(i) for i in range(10001)])
        self.symbols = {'binary_sha256': 'b' * 64, 'reader_go_version': 'go1.27.2',
                        'function_count': len(functions), 'functions': functions}
        self.candidate = {'binary_sha256': 'b' * 64, 'observed_module_notices': ['GO-FIXTURE'],
                          'binary_functions': {'count': len(functions), 'names_sha256':
                              EVIDENCE.sha(json.dumps(functions, separators=(',', ':')).encode())}}
        self.streams = {}
        self.receipt = {'analysis': {}}
        for mode in ('source', 'binary'):
            self.streams[mode] = [
                {'config': {'scan_mode': mode, 'scan_level': 'symbol', 'scanner_name': 'govulncheck',
                            'scanner_version': BUILD.TOOL, 'db': 'https://vuln.go.dev',
                            'db_last_modified': (datetime.now(timezone.utc) - timedelta(hours=1)).isoformat()}},
                {'SBOM': {'go_version': 'go1.27.2', 'roots': [self.metadata['path' if mode == 'source' else 'main_module']],
                          'modules': [{'path': 'golang.org/x/net', 'version': 'v0.60.0'}]}},
                {'osv': {'id': 'GO-FIXTURE', 'affected': [{'package': {'name': 'golang.org/x/crypto'},
                         'ecosystem_specific': {'imports': [{'path': 'golang.org/x/crypto/openpgp'}]}}]}},
                {'finding': {'osv': 'GO-FIXTURE', 'trace': [{'module': 'golang.org/x/crypto'}]}}]

    def validate(self):
        (self.directory / 'binary-symbols.json').write_text(json.dumps(self.symbols))
        for mode, stream in self.streams.items():
            data = ''.join(json.dumps(m) + '\n' for m in stream).encode()
            (self.directory / (mode + '-govulncheck.json')).write_bytes(data)
            self.receipt['analysis'].setdefault(mode, {'status': 0})['sha256'] = EVIDENCE.sha(data)
        return EVIDENCE.advisory_analysis(self.candidate, self.receipt, self.directory, self.metadata, self.packages)

    def test_module_notice_remains_visible_only_when_all_affected_code_is_absent(self):
        result = self.validate()
        self.assertEqual(['GO-FIXTURE'], result['source']['module_notices'])
        self.assertEqual(0, result['binary']['callable_findings'])

    def test_advisory_service_error_cannot_be_zero_findings(self):
        self.receipt['analysis']['source'] = {'status': 2}
        with self.assertRaisesRegex(ValueError, 'failed independent'):
            self.validate()

    def test_self_scan_cannot_substitute_for_independent_authority(self):
        self.streams['binary'][0]['config']['scanner_name'] = 'trivy'
        with self.assertRaisesRegex(ValueError, 'authority'):
            self.validate()

    def test_unavailable_or_stale_service_cannot_be_clean(self):
        self.streams['source'][0]['config']['db_last_modified'] = '2026-01-01T00:00:00Z'
        with self.assertRaisesRegex(ValueError, 'Stale'):
            self.validate()

    def test_future_advisory_metadata_is_rejected(self):
        self.streams['source'][0]['config']['db_last_modified'] = '2099-01-01T00:00:00Z'
        with self.assertRaisesRegex(ValueError, 'Stale'):
            self.validate()

    def test_complete_compiled_module_inventory_is_required(self):
        self.streams['binary'][1]['SBOM']['modules'] = []
        with self.assertRaisesRegex(ValueError, 'omitted compiled'):
            self.validate()

    def test_incomplete_function_readback_is_rejected(self):
        self.symbols['functions'].pop()
        self.symbols['function_count'] -= 1
        with self.assertRaisesRegex(ValueError, 'function readback|function census'):
            self.validate()

    def test_actual_callable_finding_is_rejected(self):
        self.streams['binary'][-1]['finding']['trace'][0].update(package='golang.org/x/crypto/openpgp', function='ReadMessage')
        with self.assertRaisesRegex(ValueError, 'callable'):
            self.validate()

    def test_affected_compiled_package_cannot_borrow_module_only_notice(self):
        self.packages.append({'ImportPath': 'golang.org/x/crypto/openpgp', 'Module': {'Path': 'golang.org/x/crypto'}})
        with self.assertRaisesRegex(ValueError, 'compiled scanner'):
            self.validate()

    def test_new_advisory_requires_review(self):
        self.streams['source'][-1]['finding']['osv'] = 'GO-UNREVIEWED'
        with self.assertRaisesRegex(ValueError, 'Unexpected independent'):
            self.validate()

    def test_missing_advisory_body_is_rejected(self):
        del self.streams['source'][2]
        with self.assertRaisesRegex(ValueError, 'authoritative advisory body'):
            self.validate()

    def test_analyzer_error_message_is_rejected(self):
        self.streams['binary'].append({'error': 'database unavailable'})
        with self.assertRaisesRegex(ValueError, 'service failure'):
            self.validate()

    def test_missing_upstream_provenance_fails_closed(self):
        with self.assertRaises(FileNotFoundError):
            EVIDENCE.validate_upstream(self.directory)

    def test_replaced_module_build_information_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Unsupported/replaced'):
            EVIDENCE.buildinfo('fixture: go1.27.2\n\t=>\tlocal/module\n')

    def test_truncated_stream_is_rejected(self):
        with self.assertRaises(json.JSONDecodeError):
            EVIDENCE.messages(b'{"config":')


class TrivyCiTrustTest(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location('auxiliary', ROOT / 'scripts/validate-auxiliary-containers.py')
        self.policy = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.policy)
        self.contract = json.loads(self.policy.CONTRACT.read_text())

    def test_scanner_cannot_be_removed_from_governance(self):
        self.contract['images']['scanner']['governed'] = False
        with self.assertRaisesRegex(ValueError, 'governance'):
            self.policy.inventory(self.contract)

    def test_scanner_threshold_cannot_be_lowered(self):
        self.contract['images']['scanner']['threshold'] = ['CRITICAL']
        with self.assertRaisesRegex(ValueError, 'threshold'):
            self.policy.inventory(self.contract)

    def test_missing_reproducible_build_receipt_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaises(FileNotFoundError):
                EVIDENCE.validate_build(json.loads(EVIDENCE.CONTRACT.read_text()), Path(temporary), 'current')

    def test_upstream_attestation_cannot_be_claimed_for_rebuilt_executable(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'upstream.json'
            path.write_text(json.dumps({'verified': True, 'image': self.contract['images']['scanner']['reference']}))
            with self.assertRaisesRegex(ValueError, 'upstream source'):
                EVIDENCE.validate_upstream(Path(temporary))


if __name__ == '__main__':
    unittest.main()
