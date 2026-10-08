import importlib.util
import unittest
from pathlib import Path


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


if __name__ == '__main__':
    unittest.main()


class BuilderEvidencePolicyTest(unittest.TestCase):
    def setUp(self):
        self.packages = {'unzip': '6.0-26ubuntu3.2', 'openssl': '3.0.2-test', 'bash': '5.1-test'}
        self.report = {
            'SchemaVersion': 2, 'ArtifactType': 'container_image', 'Trivy': {'Version': '0.72.0'},
            'Metadata': {'ImageID': 'sha256:test', 'OS': {'Family': 'ubuntu', 'Name': '22.04'}},
            'Results': [{'Class': 'os-pkgs', 'Type': 'ubuntu', 'Target': 'Ubuntu',
                         'Packages': [{'Name': k, 'Version': v} for k, v in self.packages.items()],
                         'Vulnerabilities': []}],
        }
        self.bom = {'bomFormat': 'CycloneDX', 'specVersion': '1.6',
                    'metadata': {'component': {'name': 'enterprise-shop/builder:ci'}},
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

    def test_missing_or_nonfinite_severity_fails_closed(self):
        for score in (None, float('nan'), 11):
            with self.assertRaises(ValueError):
                POLICY.jdk_findings(self.vdr(score=score), '21.0.1')


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
        self.assertIn('TRIVY_IMAGE: ghcr.io/aquasecurity/trivy:0.72.0@sha256:cffe3f5161a47a6823fbd23d985795b3ed72a4c806da4c4df16266c02accdd6f', self.job)
        self.assertIn('--list-all-pkgs --exit-code 0', self.scanner)
        self.assertIn('--ignorefile /dev/null', self.scanner)
        self.assertIn('image --download-db-only', self.scanner)
        self.assertIn('scanner-exit-status.txt', self.scanner)
        self.assertIn('sbom-exit-status.txt', self.scanner)
        self.assertIn('database-exit-status.txt', self.scanner)
        for disallowed in ('--ignore-unfixed', '.trivyignore', '--severity', '|| true'):
            self.assertNotIn(disallowed, self.scanner)
