import importlib.util
from pathlib import Path
import sys
import unittest


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "production_contract", ROOT / "scripts/validate-production-configuration.py")
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


def contract_row(variable, property_path="value", syntax="required"):
    return f"| `{variable}` | `{property_path}` | {syntax} | details |\n"


class ProductionConfigurationContractTest(unittest.TestCase):
    def test_extracts_required_placeholder(self):
        self.assertEqual(MODULE.extract_placeholders("value: ${REQUIRED}\n")[0].syntax, "required")

    def test_extracts_defaulted_placeholder(self):
        placeholder = MODULE.extract_placeholders("value: ${DEFAULTED:PT1S}\n")[0]
        self.assertEqual((placeholder.syntax, placeholder.default), ("defaulted", "PT1S"))

    def test_extracts_empty_default_placeholder(self):
        self.assertEqual(MODULE.extract_placeholders("value: ${OPTIONAL:}\n")[0].syntax, "empty-default")

    def test_extracts_nested_fallback(self):
        placeholders = MODULE.extract_placeholders("spring:\n  flyway:\n    url: ${FLYWAY_URL:${DATABASE_URL}}\n")
        self.assertEqual(
            [(item.variable, item.property_path, item.syntax, item.default) for item in placeholders],
            [("FLYWAY_URL", "spring.flyway.url", "defaulted", "${DATABASE_URL}"),
             ("DATABASE_URL", "spring.flyway.url", "required", None)])

    def test_rejects_duplicate_contract_rows(self):
        with self.assertRaisesRegex(MODULE.ContractError, "duplicate"):
            MODULE.parse_contract(contract_row("VALUE") + contract_row("VALUE"))

    def test_rejects_undocumented_production_variable(self):
        with self.assertRaisesRegex(MODULE.ContractError, "undocumented"):
            MODULE.validate("value: ${NEW_VALUE}\n", contract_row("OTHER_VALUE"))

    def test_rejects_stale_documented_variable(self):
        with self.assertRaisesRegex(MODULE.ContractError, "stale"):
            MODULE.validate("value: ${VALUE}\n", contract_row("VALUE") + contract_row("OLD_VALUE"))

    def test_current_production_configuration_passes(self):
        MODULE.validate(
            (ROOT / "src/main/resources/application-prod.yml").read_text(encoding="utf-8"),
            (ROOT / "docs/operations/production-configuration.md").read_text(encoding="utf-8"))

    def test_local_dotenv_is_not_a_production_manifest(self):
        self.assertEqual(MODULE.PRODUCTION_CONFIG, ROOT / "src/main/resources/application-prod.yml")
        self.assertNotEqual(MODULE.PRODUCTION_CONFIG, ROOT / ".env.example")

    def test_rejects_unsupported_placeholder_syntax(self):
        with self.assertRaisesRegex(MODULE.ContractError, "unsupported placeholder"):
            MODULE.extract_placeholders("value: ${lowercase}\n")


if __name__ == "__main__":
    unittest.main()
