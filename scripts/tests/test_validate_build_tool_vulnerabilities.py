import copy
import importlib.util
import json
import unittest
from pathlib import Path

SCRIPT = Path(__file__).parents[1] / 'validate-build-tool-vulnerabilities.py'
SPEC = importlib.util.spec_from_file_location('build_tool_policy', SCRIPT)
POLICY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(POLICY)
FIXTURES = Path(__file__).parent / 'fixtures/build-tool-sbom'


def evidence(document, severity=None):
    item = document['components'][0]
    identity = item['purl']
    value = {'SchemaVersion': 2, 'ArtifactType': 'cyclonedx', 'Trivy': {'Version': '0.75.0'},
             'Results': [{'Class': 'lang-pkgs', 'Type': 'jar', 'Packages': [
                 {'Name': item['group'] + ':' + item['name'], 'Version': item['version'],
                  'Identifier': {'PURL': identity}}]}]}
    if severity:
        value['Results'][0]['Vulnerabilities'] = [{'VulnerabilityID': 'CVE-2025-48734',
            'PkgName': item['group'] + ':' + item['name'], 'InstalledVersion': item['version'],
            'PkgIdentifier': {'PURL': identity}, 'Severity': severity}]
    return value


class BuildToolVulnerabilityPolicyTest(unittest.TestCase):
    def setUp(self):
        self.vulnerable = json.loads((FIXTURES / 'vulnerable.cdx.json').read_text())
        self.patched = json.loads((FIXTURES / 'patched.cdx.json').read_text())

    def test_isolated_vulnerable_fixture_is_blocked_at_reviewed_severity(self):
        findings = POLICY.validate(self.vulnerable, evidence(self.vulnerable, 'HIGH'), 0)
        self.assertEqual([('pkg:maven/commons-beanutils/commons-beanutils@1.9.4',
                           'CVE-2025-48734', 'HIGH')], findings)

    def test_patched_fixture_clean_evidence_passes(self):
        self.assertEqual([], POLICY.validate(self.patched, evidence(self.patched), 0))

    def test_high_and_critical_block_without_application_exceptions(self):
        for severity in ('HIGH', 'CRITICAL'):
            self.assertTrue(POLICY.validate(self.patched, evidence(self.patched, severity), 0))

    def test_scanner_network_or_database_failure_cannot_be_clean(self):
        for status in (1, 2, 127, -9, False, 0.0):
            with self.subTest(status=status), self.assertRaisesRegex(ValueError, 'failure'):
                POLICY.validate(self.patched, evidence(self.patched), status)

    def test_missing_or_duplicate_package_readback_fails(self):
        for packages in (None, [], [evidence(self.patched)['Results'][0]['Packages'][0]] * 2):
            report = evidence(self.patched)
            report['Results'][0]['Packages'] = packages
            with self.subTest(packages=packages), self.assertRaises(ValueError):
                POLICY.validate(self.patched, report, 0)

    def test_scanner_ignored_input_component_fails(self):
        document = copy.deepcopy(self.patched)
        document['components'].extend(self.vulnerable['components'])
        with self.assertRaisesRegex(ValueError, 'omitted'):
            POLICY.validate(document, evidence(self.patched), 0)

    def test_wrong_scanner_format_version_and_language_fail(self):
        for field, value in (('SchemaVersion', 1), ('ArtifactType', 'container_image'),
                             ('Trivy', {'Version': 'latest'}), ('Results', [])):
            report = evidence(self.patched); report[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                POLICY.validate(self.patched, report, 0)
        report = evidence(self.patched); report['Results'][0]['Type'] = 'os'
        with self.assertRaises(ValueError):
            POLICY.validate(self.patched, report, 0)

    def test_wrong_package_or_vulnerability_identity_fails(self):
        report = evidence(self.patched)
        report['Results'][0]['Packages'][0]['Version'] = '1.9.4'
        with self.assertRaises(ValueError):
            POLICY.validate(self.patched, report, 0)
        report = evidence(self.patched, 'HIGH')
        report['Results'][0]['Vulnerabilities'][0]['InstalledVersion'] = '1.9.4'
        with self.assertRaises(ValueError):
            POLICY.validate(self.patched, report, 0)

    def test_malformed_vulnerability_severity_or_list_fails(self):
        report = evidence(self.patched, 'high')
        with self.assertRaises(ValueError):
            POLICY.validate(self.patched, report, 0)
        for value in ('', {}, 0):
            report = evidence(self.patched); report['Results'][0]['Vulnerabilities'] = value
            with self.subTest(value=value), self.assertRaises(ValueError):
                POLICY.validate(self.patched, report, 0)


if __name__ == '__main__':
    unittest.main()
