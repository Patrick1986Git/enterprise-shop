import copy
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "openapi_compatibility.py"
SPEC = importlib.util.spec_from_file_location("openapi_compatibility", SCRIPT)
COMPATIBILITY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(COMPATIBILITY)


def contract():
    return {
        "openapi": "3.0.1",
        "security": [{"bearerAuth": []}],
        "paths": {
            "/orders/{id}": {
                "get": {
                    "operationId": "getOrder",
                    "parameters": [{
                        "name": "id", "in": "path", "required": True,
                        "schema": {"type": "string", "format": "uuid"},
                    }],
                    "responses": {
                        "200": {"content": {"application/json": {"schema": {
                            "type": "object",
                            "properties": {"id": {"type": "string"}, "status": {"type": "string"}},
                        }}}},
                        "404": {"description": "Not found"},
                    },
                },
                "post": {
                    "operationId": "updateOrder",
                    "requestBody": {"content": {"application/json": {"schema": {
                        "type": "object",
                        "required": ["status"],
                        "properties": {"status": {"type": "string"}},
                    }}}},
                    "responses": {"204": {"description": "Updated"}},
                },
            }
        },
    }


class OpenApiCompatibilityTest(unittest.TestCase):
    def findings(self, mutate):
        baseline = contract()
        candidate = copy.deepcopy(baseline)
        mutate(candidate)
        return COMPATIBILITY.compare_documents(baseline, candidate)

    def test_detects_removed_operation(self):
        findings = self.findings(lambda value: value["paths"]["/orders/{id}"].pop("get"))
        self.assertTrue(any("operation was removed" in finding for finding in findings))

    def test_detects_new_required_request_property(self):
        def mutate(value):
            schema = value["paths"]["/orders/{id}"]["post"]["requestBody"]["content"]["application/json"]["schema"]
            schema["properties"]["reason"] = {"type": "string"}
            schema["required"].append("reason")
        self.assertTrue(any("request property became required" in finding for finding in self.findings(mutate)))

    def test_detects_new_required_parameter(self):
        def mutate(value):
            value["paths"]["/orders/{id}"]["get"]["parameters"].append(
                {"name": "tenant", "in": "header", "required": True, "schema": {"type": "string"}}
            )
        self.assertTrue(any("required parameter" in finding for finding in self.findings(mutate)))

    def test_detects_response_property_removal_and_type_change(self):
        def remove(value):
            del value["paths"]["/orders/{id}"]["get"]["responses"]["200"]["content"]["application/json"]["schema"]["properties"]["status"]
        def change(value):
            value["paths"]["/orders/{id}"]["get"]["responses"]["200"]["content"]["application/json"]["schema"]["properties"]["id"]["type"] = "integer"
        self.assertTrue(any("property was removed" in finding for finding in self.findings(remove)))
        self.assertTrue(any("schema type changed" in finding for finding in self.findings(change)))

    def test_detects_security_requirement_change(self):
        findings = self.findings(lambda value: value["paths"]["/orders/{id}"]["get"].update({"security": []}))
        self.assertTrue(any("security requirements changed" in finding for finding in findings))

    def test_allows_new_endpoint_optional_request_and_response_properties(self):
        def mutate(value):
            request = value["paths"]["/orders/{id}"]["post"]["requestBody"]["content"]["application/json"]["schema"]
            request["properties"]["note"] = {"type": "string"}
            response = value["paths"]["/orders/{id}"]["get"]["responses"]["200"]["content"]["application/json"]["schema"]
            response["properties"]["label"] = {"type": "string"}
            value["paths"]["/health"] = {"get": {"operationId": "health", "responses": {"200": {"description": "OK"}}}}
        self.assertEqual(set(), self.findings(mutate))

    def test_fails_closed_for_missing_document_and_bad_provenance(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            baseline, candidate = root / "baseline", root / "candidate"
            baseline.mkdir()
            candidate.mkdir()
            (baseline / "baseline-provenance.json").write_text(
                json.dumps({"repository": "Patrick1986Git/enterprise-shop", "sha": "wrong"}), encoding="utf-8")
            with self.assertRaises(COMPATIBILITY.ContractError):
                COMPATIBILITY.check_directories(baseline, candidate, "expected")


if __name__ == "__main__":
    unittest.main()
