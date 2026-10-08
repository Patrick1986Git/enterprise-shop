import importlib.util
import contextlib
import io
import re
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('scan_build_tools', ROOT / 'scripts/scan-build-tools.py')
SCAN = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SCAN)


class BuildToolCiPolicyTest(unittest.TestCase):
    def setUp(self):
        self.workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        self.build = self.workflow.split('  build:\n', 1)[1].split('  docker-validation:\n', 1)[0]

    def test_same_pinned_trivy_authority_is_used_for_images_and_build_tools(self):
        image = re.search(r'TRIVY_IMAGE: (\S+)', self.workflow)[1]
        self.assertEqual(image, SCAN.TRIVY_IMAGE)
        self.assertRegex(image, r'^ghcr\.io/aquasecurity/trivy:0\.75\.0@sha256:[0-9a-f]{64}$')

    def test_readonly_pr_gate_and_protected_master_schedule_cannot_silently_disappear(self):
        self.assertIn("github.event_name == 'pull_request'", self.build)
        self.assertIn("github.event_name == 'schedule'", self.build)
        self.assertIn("github.event_name == 'schedule' && 'master' || github.event.pull_request.head.sha || github.ref", self.build)
        self.assertNotIn('contents: write', self.build)
        self.assertNotIn('pull_request_target', self.build)
        self.assertNotIn('secrets.', self.build)
        self.assertEqual(1, self.build.count('clean verify'))
        for command in ('python scripts/build-tool-inventory.py prepare',
                        'python scripts/build-tool-inventory.py collect',
                        'python scripts/build-tool-inventory.py validate-bom',
                        'python scripts/scan-build-tools.py', 'dependency:go-offline',
                        '-Dbuildtools.mode=verify', 'python scripts/build-tool-inventory.py observe-commands'):
            self.assertIn(command, self.build)

    def test_security_failure_preserves_reviewable_evidence(self):
        artifact = self.build.split('- name: Upload build-tool security evidence', 1)[1]
        self.assertIn('if: always()', artifact)
        self.assertIn('name: build-tool-security-evidence', artifact)
        self.assertIn('if-no-files-found: error', artifact)
        self.assertIn('include-hidden-files: true', artifact)

    def test_collector_asset_is_compiled_with_java21_and_all_warnings_as_errors(self):
        source = (ROOT / 'scripts/build-tool-inventory.py').read_text()
        self.assertIn("['javac', '--release', '21', '-Xlint:all', '-Werror'", source)
        self.assertTrue((ROOT / 'scripts/build-tool-inventory/BuildToolEvidence.java.source').exists())
        self.assertFalse((ROOT / 'scripts/build-tool-inventory/BuildToolEvidence.java').exists())

    def test_failed_scan_removes_previous_policy_and_fixture_results(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / 'enterprise-shop-build-tools.cdx.json').write_text('{')
            stale = ['policy-result.json', 'scanner-fixtures.json',
                     'enterprise-shop-build-tools.trivy.json']
            for name in stale:
                (directory / name).write_text('{}')
            with patch('sys.argv', ['scan', '--directory', temporary]), \
                    contextlib.redirect_stderr(io.StringIO()), patch.object(SCAN, 'docker') as docker:
                self.assertEqual(2, SCAN.main())
                docker.assert_not_called()
            self.assertTrue(all(not (directory / name).exists() for name in stale))

    def test_tampered_execution_scope_fails_before_the_scanner_is_invoked(self):
        contract = SCAN.INVENTORY.read_json(SCAN.INVENTORY.CONTRACT)
        inventory = contract['inventory']
        expected = {'schema': 1, 'executed_components': inventory['components']}
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            SCAN.INVENTORY.write_json(directory / 'enterprise-shop-build-tools.cdx.json',
                                     SCAN.INVENTORY.bom(inventory))
            SCAN.INVENTORY.write_json(directory / 'inventory.json', inventory)
            SCAN.INVENTORY.write_json(directory / 'execution-scope.json',
                                     {**expected, 'executed_components': []})
            with patch('sys.argv', ['scan', '--directory', temporary]), \
                    patch.object(SCAN.INVENTORY, 'collect', return_value=(inventory, expected)), \
                    contextlib.redirect_stderr(io.StringIO()), patch.object(SCAN, 'docker') as docker:
                self.assertEqual(2, SCAN.main())
                docker.assert_not_called()


if __name__ == '__main__':
    unittest.main()
