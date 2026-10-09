import copy
import importlib.util
import json
import shutil
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).parents[2]
SPEC = importlib.util.spec_from_file_location('auxiliary_policy', ROOT / 'scripts/validate-auxiliary-containers.py')
POLICY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(POLICY)


class AuxiliaryPolicyTest(unittest.TestCase):
    def setUp(self):
        self.contract = POLICY.read(POLICY.CONTRACT)
        self.image = copy.deepcopy(self.contract['images']['govulncheck'])
        self.image['required_gobinaries'] = ['usr/local/go/bin/go']
        self.image_id = 'sha256:' + 'a' * 64
        now = datetime.now(timezone.utc)
        self.evidence = {'reference': self.image['reference'], 'platform': 'linux/amd64',
                         'image_id': self.image_id, 'resolved_digest': 'sha256:' + 'b' * 64,
                         'identity': self.image['identity'], 'database_status': 0, 'scanner_status': 0,
                         'timestamp': now.isoformat(),
                         'sbom_status': 0, 'independent_scanner_integrity': False,
                         'database': {'Version': 2, 'UpdatedAt': (now - timedelta(hours=1)).isoformat(),
                                      'NextUpdate': (now + timedelta(hours=5)).isoformat()},
                         'installed_os_packages': {'musl': '1.2.5-r12'}, 'artifact_name': '/input/govulncheck.tar'}
        self.report = {'SchemaVersion': 2, 'ArtifactType': 'container_image',
                       'ArtifactName': '/input/govulncheck.tar', 'Trivy': {'Version': '0.75.0'},
                       'Metadata': {'ImageID': self.image_id}, 'Results': [
                           {'Target': 'Alpine', 'Class': 'os-pkgs', 'Type': 'alpine', 'Packages': [
                               {'Name': 'musl', 'Version': '1.2.5-r12', 'Identifier': {'PURL': 'pkg:apk/alpine/musl@1.2.5-r12'}}]},
                           {'Target': 'usr/local/go/bin/go', 'Class': 'lang-pkgs', 'Type': 'gobinary', 'Packages': [
                               {'Name': 'stdlib', 'Version': 'v1.26.8', 'Identifier': {'PURL': 'pkg:golang/stdlib@v1.26.8'}}]}]}
        self.bom = {'bomFormat': 'CycloneDX', 'specVersion': '1.7', 'metadata': {'component': {
            'name': '/input/govulncheck.tar', 'properties': [{'name': 'aquasecurity:trivy:ImageID', 'value': self.image_id}]}},
            'components': [{'purl': 'pkg:apk/alpine/musl@1.2.5-r12'}, {'purl': 'pkg:golang/stdlib@v1.26.8'}]}

    def validate(self):
        return POLICY.validate(self.image, self.evidence, self.report, self.bom, '0.75.0')

    def finding(self, severity):
        self.report['Results'][0]['Vulnerabilities'] = [{'VulnerabilityID': 'CVE-2026-1234',
            'PkgName': 'musl', 'InstalledVersion': '1.2.5-r12', 'Severity': severity}]

    def source_change(self, change):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            paths = set(self.contract['execution_sources']) | {'src/test/resources/testcontainers.properties'}
            for name in paths:
                target = root / name
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(ROOT / name, target)
            change(root)
            with self.assertRaises(ValueError):
                POLICY.inventory(self.contract, root)

    def test_complete_executed_image_inventory_includes_library_ryuk(self):
        self.assertEqual({'hadolint', 'scanner', 'govulncheck', 'fixture', 'ryuk'}, set(POLICY.inventory(self.contract)))

    def test_new_workflow_container_cannot_bypass_review(self):
        self.source_change(lambda r: (r / '.github/workflows/new.yml').write_text('jobs:\n  new:\n    container: ubuntu:latest\n'))

    def test_new_docker_run_cannot_bypass_review(self):
        self.source_change(lambda r: (r / 'scripts/new.sh').write_text('docker run ubuntu:latest sh -c true\n'))

    def test_new_testcontainers_image_cannot_bypass_review(self):
        def add(root):
            path = root / 'src/test/New.java'
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('new GenericContainer("ubuntu:latest");')
        self.source_change(add)

    def test_dynamic_command_change_requires_review(self):
        self.source_change(lambda r: (r / 'scripts/run-gosu-govulncheck.sh').write_text('docker run "$UNREVIEWED_IMAGE"\n'))

    def test_downloaded_gosu_dockerfiles_are_not_repository_execution_surfaces(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / '.tmp/gosu-source/Dockerfile'
            path.parent.mkdir(parents=True)
            path.write_text('FROM golang:latest\n')
            self.assertEqual({}, POLICY.execution_sources(root))

    def test_timestamp_is_required_and_must_be_fresh(self):
        self.evidence['timestamp'] = (datetime.now(timezone.utc) - timedelta(days=2)).isoformat()
        with self.assertRaisesRegex(ValueError, 'timestamp'): self.validate()

    def test_opaque_binary_cannot_borrow_empty_hadolint_boundary(self):
        self.contract['images']['ryuk']['package_mode'] = 'static-haskell'
        with self.assertRaisesRegex(ValueError, 'Hadolint'): POLICY.inventory(self.contract)

    def test_digest_required(self):
        self.contract['images']['govulncheck']['reference'] = 'docker.io/library/golang:1.26.8-alpine3.24'
        with self.assertRaises(ValueError): POLICY.inventory(self.contract)

    def test_linux_amd64_required(self):
        self.evidence['platform'] = 'linux/arm64'
        with self.assertRaises(ValueError): self.validate()

    def test_expected_executable_identity_required(self):
        self.evidence['identity'] = {'binary': '/other'}
        with self.assertRaises(ValueError): self.validate()

    def test_expected_go_version_readback_required(self):
        self.report['Results'][1]['Packages'][0]['Version'] = 'v1.25.7'
        with self.assertRaises(ValueError): self.validate()

    def test_high_blocks(self):
        self.finding('HIGH')
        self.assertEqual('HIGH', self.validate()['blocked'][0]['severity'])

    def test_critical_blocks(self):
        self.finding('CRITICAL')
        self.assertEqual('CRITICAL', self.validate()['blocked'][0]['severity'])

    def test_lower_severity_remains_visible_without_block(self):
        self.finding('MEDIUM')
        self.assertEqual([], self.validate()['blocked'])

    def test_malformed_raw_report_fails(self):
        self.report['Results'] = {}
        with self.assertRaises(ValueError): self.validate()

    def test_empty_object_findings_are_not_clean(self):
        self.report['Results'][0]['Vulnerabilities'] = {}
        with self.assertRaises(ValueError): self.validate()

    def test_scanner_database_and_sbom_failure_fail(self):
        for key in ('scanner_status', 'database_status', 'sbom_status'):
            with self.subTest(key=key):
                self.evidence[key] = 1
                with self.assertRaises(ValueError): self.validate()
                self.evidence[key] = 0

    def test_wrong_sbom_image_fails(self):
        self.bom['metadata']['component']['properties'][0]['value'] = 'sha256:' + 'c' * 64
        with self.assertRaises(ValueError): self.validate()

    def test_missing_os_package_readback_fails(self):
        self.report['Results'][0]['Packages'] = []
        with self.assertRaises(ValueError): self.validate()

    def test_missing_sbom_package_readback_fails(self):
        self.bom['components'].pop()
        with self.assertRaises(ValueError): self.validate()

    def test_compressed_ryuk_empty_report_is_coverage_error(self):
        self.image['required_gobinaries'] = ['bin/ryuk']
        self.report['Results'] = []
        self.bom['components'] = []
        self.evidence['installed_os_packages'] = {}
        with self.assertRaisesRegex(ValueError, 'Missing executable package readback'): self.validate()

    def test_postgres_gosu_exception_cannot_exempt_tool(self):
        self.report['Results'][1]['Target'] = 'usr/local/bin/gosu'
        self.image['required_gobinaries'] = ['usr/local/bin/gosu']
        self.report['Results'][1]['Vulnerabilities'] = [{'VulnerabilityID': 'CVE-2025-68121',
            'PkgName': 'stdlib', 'InstalledVersion': 'v1.26.8', 'Severity': 'CRITICAL'}]
        self.assertEqual(1, len(self.validate()['blocked']))

    def test_no_auxiliary_exception_authority_can_exempt_production(self):
        self.contract['exceptions'] = [{'image': 'application', 'id': 'CVE-2026-1234'}]
        with self.assertRaises(ValueError): POLICY.inventory(self.contract)
        source = (ROOT / 'scripts/validate-container-vulnerability-policy.py').read_text()
        self.assertNotIn('auxiliary', source)

    def test_fixture_is_mechanically_distinct_from_execution_evidence(self):
        self.image = self.contract['images']['fixture']
        with self.assertRaisesRegex(ValueError, 'Fixture'): self.validate()

    def test_vulnerable_fixture_cannot_be_promoted_silently(self):
        self.contract['images']['fixture']['governed'] = True
        with self.assertRaises(ValueError): POLICY.inventory(self.contract)

    def test_self_scan_cannot_claim_independent_integrity(self):
        self.evidence['independent_scanner_integrity'] = True
        with self.assertRaisesRegex(ValueError, 'Self-scan'): self.validate()

    def test_self_scan_cannot_replace_upstream_provenance(self):
        self.contract['scanner_independent_integrity'] = 'self-scan-success'
        with self.assertRaisesRegex(ValueError, 'Self-scan'): POLICY.inventory(self.contract)

    def test_scheduled_coverage_cannot_disappear(self):
        self.source_change(lambda r: (r / '.github/workflows/ci.yml').write_text(
            (r / '.github/workflows/ci.yml').read_text().replace('    - cron: "23 4 * * 1"', '')))

    def test_pr_execution_cannot_change_to_merge_ref_or_write(self):
        for before, after in [('github.event.pull_request.head.sha', 'github.sha'), ('  contents: read', '  contents: write')]:
            self.source_change(lambda r: (r / '.github/workflows/ci.yml').write_text(
                (r / '.github/workflows/ci.yml').read_text().replace(before, after)))

    def test_new_classification_must_be_explicit(self):
        self.contract['images']['ryuk']['classification'] = 'CI-only'
        with self.assertRaisesRegex(ValueError, 'classification'): POLICY.inventory(self.contract)

    def test_archive_scanner_has_no_docker_socket_and_no_exception_file(self):
        source = (ROOT / 'scripts/scan-auxiliary-containers.py').read_text()
        self.assertNotIn('/var/run/docker.sock', source)
        self.assertIn("'--ignorefile', '/dev/null'", source)
        self.assertNotIn('--ignore-unfixed', source)


if __name__ == '__main__':
    unittest.main()
