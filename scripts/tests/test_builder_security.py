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
