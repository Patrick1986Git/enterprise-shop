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

    def request_schema(self, value):
        return value["paths"]["/orders/{id}"]["post"]["requestBody"]["content"]["application/json"]["schema"]

    def response_schema(self, value):
        return value["paths"]["/orders/{id}"]["get"]["responses"]["200"]["content"]["application/json"]["schema"]

    def property_findings(self, direction, old_property, new_property, required=()):
        baseline = contract()
        candidate = copy.deepcopy(baseline)
        accessor = self.request_schema if direction == "request" else self.response_schema
        old_schema, new_schema = accessor(baseline), accessor(candidate)
        old_schema["properties"] = {"value": old_property}
        new_schema["properties"] = {"value": new_property}
        old_schema["required"] = list(required[0]) if required else []
        new_schema["required"] = list(required[1]) if required else []
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

    def test_request_variance_rejects_narrowing(self):
        cases = (
            ({"type": "string", "nullable": True}, {"type": "string"}, "non-nullable"),
            ({"type": "string"}, {"type": "string", "enum": ["A"]}, "enum restriction"),
            ({"type": "string", "enum": ["A", "B"]}, {"type": "string", "enum": ["A"]}, "enum values removed"),
            ({"type": "number", "minimum": 1}, {"type": "number", "minimum": 2}, "minimum"),
            ({"type": "number", "maximum": 10}, {"type": "number", "maximum": 9}, "maximum"),
            ({"type": "string", "minLength": 1}, {"type": "string", "minLength": 2}, "minLength"),
            ({"type": "array", "maxItems": 4}, {"type": "array", "maxItems": 3}, "maxItems"),
            ({"type": "object", "minProperties": 1}, {"type": "object", "minProperties": 2}, "minProperties"),
        )
        for old, new, expected in cases:
            with self.subTest(expected=expected):
                self.assertTrue(any(expected in finding for finding in self.property_findings("request", old, new)))
        findings = self.property_findings(
            "request", {"type": "string"}, {"type": "string"}, ((), ("value",)))
        self.assertTrue(any("request property became required" in finding for finding in findings))

    def test_request_variance_allows_broadening(self):
        cases = (
            ({"type": "string"}, {"type": "string", "nullable": True}),
            ({"type": "string", "enum": ["A"]}, {"type": "string"}),
            ({"type": "number", "minimum": 2}, {"type": "number", "minimum": 1}),
            ({"type": "number", "minimum": 2}, {"type": "number"}),
            ({"type": "number", "maximum": 9}, {"type": "number", "maximum": 10}),
            ({"type": "number", "maximum": 9}, {"type": "number"}),
        )
        for old, new in cases:
            with self.subTest(old=old, new=new):
                self.assertEqual(set(), self.property_findings("request", old, new))

    def test_response_variance_rejects_widening(self):
        cases = (
            ({"type": "string"}, {"type": "string", "nullable": True}, "became nullable"),
            ({"type": "string", "enum": ["A"]}, {"type": "string", "enum": ["A", "B"]}, "enum values added"),
            ({"type": "string", "enum": ["A"]}, {"type": "string"}, "became unrestricted"),
            ({"type": "number", "minimum": 2}, {"type": "number", "minimum": 1}, "minimum"),
            ({"type": "number", "minimum": 2}, {"type": "number"}, "minimum"),
            ({"type": "number", "maximum": 9}, {"type": "number", "maximum": 10}, "maximum"),
            ({"type": "number", "maximum": 9}, {"type": "number"}, "maximum"),
            ({"type": "array", "minItems": 2}, {"type": "array", "minItems": 1}, "minItems"),
            ({"type": "object", "maxProperties": 2}, {"type": "object", "maxProperties": 3}, "maxProperties"),
            ({"type": "string"}, {"type": "integer"}, "schema type changed"),
        )
        for old, new, expected in cases:
            with self.subTest(expected=expected):
                self.assertTrue(any(expected in finding for finding in self.property_findings("response", old, new)))
        findings = self.property_findings(
            "response", {"type": "string"}, {"type": "string"}, (("value",), ()))
        self.assertTrue(any("response property became optional" in finding for finding in findings))

    def test_response_variance_allows_narrowing(self):
        cases = (
            ({"type": "string", "nullable": True}, {"type": "string"}),
            ({"type": "string", "enum": ["A", "B"]}, {"type": "string", "enum": ["A"]}),
            ({"type": "number", "minimum": 1}, {"type": "number", "minimum": 2}),
            ({"type": "number", "maximum": 10}, {"type": "number", "maximum": 9}),
        )
        for old, new in cases:
            with self.subTest(old=old, new=new):
                self.assertEqual(set(), self.property_findings("response", old, new))
        self.assertEqual(set(), self.property_findings(
            "response", {"type": "string"}, {"type": "string"}, ((), ("value",))))

    def test_pattern_and_composition_changes_fail_closed_in_both_directions(self):
        for direction in ("request", "response"):
            with self.subTest(direction=direction, constraint="pattern"):
                findings = self.property_findings(
                    direction, {"type": "string", "pattern": "[A-Z]+"}, {"type": "string"})
                self.assertTrue(any("regex subset compatibility cannot be proven" in finding for finding in findings))
            with self.subTest(direction=direction, constraint="oneOf"):
                findings = self.property_findings(
                    direction,
                    {"oneOf": [{"type": "string"}]},
                    {"oneOf": [{"type": "integer"}]},
                )
                self.assertTrue(any("composition compatibility cannot be proven" in finding for finding in findings))

    def test_supports_local_schema_response_parameter_request_body_and_path_item_references(self):
        baseline = contract()
        baseline["components"] = {
            "schemas": {"Order": self.response_schema(baseline)},
            "responses": {"OrderResponse": {"content": {"application/json": {"schema": {
                "$ref": "#/components/schemas/Order", "description": "Schema siblings apply in OpenAPI 3.1"
            }}}}},
            "parameters": {"OrderId": baseline["paths"]["/orders/{id}"]["get"]["parameters"][0]},
            "requestBodies": {"OrderUpdate": baseline["paths"]["/orders/{id}"]["post"]["requestBody"]},
            "pathItems": {"Orders": baseline["paths"]["/orders/{id}"]},
        }
        baseline["paths"]["/orders/{id}"] = {"$ref": "#/components/pathItems/Orders"}
        path = baseline["components"]["pathItems"]["Orders"]
        path["get"]["parameters"] = [{"$ref": "#/components/parameters/OrderId"}]
        path["get"]["responses"]["200"] = {"$ref": "#/components/responses/OrderResponse"}
        path["post"]["requestBody"] = {"$ref": "#/components/requestBodies/OrderUpdate"}
        candidate = copy.deepcopy(baseline)
        self.assertEqual(set(), COMPATIBILITY.compare_documents(baseline, candidate))
        candidate["components"]["schemas"]["Order"]["properties"]["id"]["type"] = "integer"
        self.assertTrue(any("schema type changed" in finding
                            for finding in COMPATIBILITY.compare_documents(baseline, candidate)))

    def test_rejects_external_malformed_and_sibling_references(self):
        mutations = (
            lambda value: value["paths"]["/orders/{id}"]["get"]["responses"]["200"].update({"$ref": "https://example.test/response"}),
            lambda value: value["paths"]["/orders/{id}"]["get"]["parameters"].__setitem__(0, {"$ref": "#/components/parameters/Missing"}),
            lambda value: value["paths"]["/orders/{id}"]["get"]["parameters"].__setitem__(0, {
                "name": "id", "in": "path", "required": True,
                "content": {"application/json": {"schema": {"type": "string"}}},
            }),
            lambda value: value["paths"]["/orders/{id}"]["post"].__setitem__("requestBody", {"$ref": "#/components/requestBodies/Body", "required": True}),
            lambda value: value["paths"].__setitem__("/orders/{id}", {"$ref": "#/components/schemas/NotAPath"}),
        )
        for mutate in mutations:
            baseline, candidate = contract(), contract()
            mutate(candidate)
            with self.subTest(mutate=mutate), self.assertRaises(COMPATIBILITY.ContractError):
                COMPATIBILITY.compare_documents(baseline, candidate)

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
