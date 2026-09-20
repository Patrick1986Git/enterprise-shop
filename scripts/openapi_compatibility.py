#!/usr/bin/env python3
"""Fail closed when generated OpenAPI contracts contain incompatible changes."""

import argparse
import json
import sys
from pathlib import Path


DOCUMENTS = (
    "openapi",
    "all-api",
    "public-api",
    "customer-api",
    "admin-api",
    "webhooks-api",
    "system-api",
)
HTTP_METHODS = {"get", "put", "post", "delete", "options", "head", "patch", "trace"}
LOWER_BOUNDS = ("minimum", "minLength", "minItems", "minProperties")
UPPER_BOUNDS = ("maximum", "maxLength", "maxItems", "maxProperties")


class ContractError(ValueError):
    """Raised when compatibility inputs cannot be trusted."""


def load_document(path):
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ContractError(f"cannot load {path}: {exc}") from exc
    if not isinstance(document, dict) or not str(document.get("openapi", "")).startswith("3."):
        raise ContractError(f"{path} is not an OpenAPI 3.x JSON document")
    return document


def json_pointer(document, reference):
    if not isinstance(reference, str) or not reference.startswith("#/"):
        raise ContractError(f"unsupported external or malformed reference: {reference!r}")
    value = document
    for token in reference[2:].split("/"):
        token = token.replace("~1", "/").replace("~0", "~")
        if not isinstance(value, dict) or token not in value:
            raise ContractError(f"unresolved reference: {reference}")
        value = value[token]
    return value


def dereference(schema, document):
    seen = set()
    while isinstance(schema, dict) and "$ref" in schema:
        reference = schema["$ref"]
        if reference in seen:
            break
        seen.add(reference)
        schema = json_pointer(document, reference)
    return schema if isinstance(schema, dict) else {}


def location(path, method, suffix=""):
    base = f"{method.upper()} {path}"
    return f"{base} {suffix}" if suffix else base


def compare_schema(old, new, old_doc, new_doc, where, direction, findings, visited=None):
    old = dereference(old, old_doc)
    new = dereference(new, new_doc)
    visited = visited or set()
    marker = (id(old), id(new), direction)
    if marker in visited:
        return
    visited.add(marker)

    for keyword in ("type", "format"):
        if keyword in old and old.get(keyword) != new.get(keyword):
            findings.add(f"{where}: schema {keyword} changed from {old.get(keyword)!r} to {new.get(keyword)!r}")
    if old.get("nullable") is True and new.get("nullable") is not True:
        findings.add(f"{where}: nullable value became non-nullable")

    old_enum = old.get("enum")
    new_enum = new.get("enum")
    if isinstance(old_enum, list) and isinstance(new_enum, list):
        old_values, new_values = set(map(repr, old_enum)), set(map(repr, new_enum))
        incompatible = old_values - new_values if direction == "request" else new_values - old_values
        if incompatible:
            change = "accepted enum values removed" if direction == "request" else "response enum values added"
            findings.add(f"{where}: {change}: {sorted(incompatible)}")

    for keyword in LOWER_BOUNDS:
        if keyword in new and (keyword not in old or new[keyword] > old[keyword]):
            findings.add(f"{where}: {keyword} tightened from {old.get(keyword)!r} to {new[keyword]!r}")
    for keyword in UPPER_BOUNDS:
        if keyword in new and (keyword not in old or new[keyword] < old[keyword]):
            findings.add(f"{where}: {keyword} tightened from {old.get(keyword)!r} to {new[keyword]!r}")
    if new.get("exclusiveMinimum") is True and old.get("exclusiveMinimum") is not True:
        findings.add(f"{where}: minimum became exclusive")
    if new.get("exclusiveMaximum") is True and old.get("exclusiveMaximum") is not True:
        findings.add(f"{where}: maximum became exclusive")
    if "pattern" in new and old.get("pattern") != new.get("pattern"):
        findings.add(f"{where}: string pattern was added or changed")

    old_required = set(old.get("required", []))
    new_required = set(new.get("required", []))
    if direction == "request":
        for name in sorted(new_required - old_required):
            findings.add(f"{where}.{name}: request property became required")

    old_properties = old.get("properties", {})
    new_properties = new.get("properties", {})
    if isinstance(old_properties, dict) and isinstance(new_properties, dict):
        for name in sorted(old_properties.keys() - new_properties.keys()):
            findings.add(f"{where}.{name}: schema property was removed")
        for name in sorted(old_properties.keys() & new_properties.keys()):
            compare_schema(
                old_properties[name], new_properties[name], old_doc, new_doc,
                f"{where}.{name}", direction, findings, visited,
            )

    if "items" in old and "items" in new:
        compare_schema(old["items"], new["items"], old_doc, new_doc, f"{where}[]", direction, findings, visited)
    for composition in ("allOf", "oneOf", "anyOf"):
        old_parts, new_parts = old.get(composition), new.get(composition)
        if isinstance(old_parts, list):
            if not isinstance(new_parts, list) or len(old_parts) != len(new_parts):
                findings.add(f"{where}: {composition} alternatives changed")
            else:
                for index, (old_part, new_part) in enumerate(zip(old_parts, new_parts)):
                    compare_schema(old_part, new_part, old_doc, new_doc,
                                   f"{where}.{composition}[{index}]", direction, findings, visited)


def parameters(path_item, operation):
    result = {}
    for parameter in [*path_item.get("parameters", []), *operation.get("parameters", [])]:
        if isinstance(parameter, dict):
            result[(parameter.get("in"), parameter.get("name"))] = parameter
    return result


def compare_content(old_content, new_content, old_doc, new_doc, where, direction, findings):
    old_content = old_content if isinstance(old_content, dict) else {}
    new_content = new_content if isinstance(new_content, dict) else {}
    for media_type in sorted(old_content.keys() - new_content.keys()):
        findings.add(f"{where}: response/request media type {media_type!r} was removed")
    for media_type in sorted(old_content.keys() & new_content.keys()):
        old_schema = old_content[media_type].get("schema", {})
        new_schema = new_content[media_type].get("schema", {})
        compare_schema(old_schema, new_schema, old_doc, new_doc,
                       f"{where} ({media_type})", direction, findings)


def normalized_security(operation, document):
    security = operation.get("security", document.get("security", []))
    return json.dumps(security, sort_keys=True, separators=(",", ":"))


def compare_operation(path, method, old_path_item, new_path_item, old_op, new_op, old_doc, new_doc, findings):
    where = location(path, method)
    if old_op.get("operationId") != new_op.get("operationId"):
        findings.add(f"{where}: operationId changed from {old_op.get('operationId')!r} to {new_op.get('operationId')!r}")
    if normalized_security(old_op, old_doc) != normalized_security(new_op, new_doc):
        findings.add(f"{where}: security requirements changed")

    old_parameters = parameters(old_path_item, old_op)
    new_parameters = parameters(new_path_item, new_op)
    for key in sorted(old_parameters.keys() - new_parameters.keys(), key=repr):
        findings.add(f"{where}: parameter {key!r} was removed")
    for key in sorted(new_parameters.keys() - old_parameters.keys(), key=repr):
        if new_parameters[key].get("required") is True:
            findings.add(f"{where}: required parameter {key!r} was added")
    for key in sorted(old_parameters.keys() & new_parameters.keys(), key=repr):
        old_parameter, new_parameter = old_parameters[key], new_parameters[key]
        if old_parameter.get("required") is not True and new_parameter.get("required") is True:
            findings.add(f"{where}: parameter {key!r} became required")
        compare_schema(old_parameter.get("schema", {}), new_parameter.get("schema", {}),
                       old_doc, new_doc, f"{where} parameter {key!r}", "request", findings)

    old_body, new_body = old_op.get("requestBody"), new_op.get("requestBody")
    if isinstance(new_body, dict) and new_body.get("required") is True and not (
            isinstance(old_body, dict) and old_body.get("required") is True):
        findings.add(f"{where}: required request body was added")
    if isinstance(old_body, dict) and isinstance(new_body, dict):
        compare_content(old_body.get("content"), new_body.get("content"), old_doc, new_doc,
                        f"{where} request body", "request", findings)

    old_responses = old_op.get("responses", {})
    new_responses = new_op.get("responses", {})
    for status in sorted(old_responses.keys() - new_responses.keys()):
        findings.add(f"{where}: documented response {status!r} was removed")
    for status in sorted(old_responses.keys() & new_responses.keys()):
        old_response = dereference(old_responses[status], old_doc)
        new_response = dereference(new_responses[status], new_doc)
        compare_content(old_response.get("content"), new_response.get("content"), old_doc, new_doc,
                        f"{where} response {status}", "response", findings)


def compare_documents(old_doc, new_doc):
    findings = set()
    old_paths, new_paths = old_doc.get("paths", {}), new_doc.get("paths", {})
    if not isinstance(old_paths, dict) or not isinstance(new_paths, dict):
        raise ContractError("OpenAPI paths must be objects")
    for path in sorted(old_paths.keys() - new_paths.keys()):
        findings.add(f"{path}: path was removed")
    for path in sorted(old_paths.keys() & new_paths.keys()):
        old_path_item, new_path_item = old_paths[path], new_paths[path]
        for method in sorted(HTTP_METHODS & old_path_item.keys() - new_path_item.keys()):
            findings.add(f"{location(path, method)}: operation was removed")
        for method in sorted(HTTP_METHODS & old_path_item.keys() & new_path_item.keys()):
            compare_operation(path, method, old_path_item, new_path_item,
                              old_path_item[method], new_path_item[method], old_doc, new_doc, findings)
    return findings


def validate_manifest(directory, expected_sha):
    manifest_path = directory / "baseline-provenance.json"
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ContractError(f"cannot load baseline provenance {manifest_path}: {exc}") from exc
    if manifest != {"repository": "Patrick1986Git/enterprise-shop", "sha": expected_sha}:
        raise ContractError(f"baseline provenance does not exactly match repository and SHA {expected_sha}")


def check_directories(baseline, candidate, expected_sha):
    validate_manifest(baseline, expected_sha)
    grouped = {}
    for name in DOCUMENTS:
        old_doc = load_document(baseline / f"{name}.json")
        new_doc = load_document(candidate / f"{name}.json")
        for finding in compare_documents(old_doc, new_doc):
            grouped.setdefault(finding, []).append(name)
    return grouped


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", required=True, type=Path)
    parser.add_argument("--candidate", required=True, type=Path)
    parser.add_argument("--baseline-sha", required=True)
    args = parser.parse_args()
    try:
        findings = check_directories(args.baseline, args.candidate, args.baseline_sha)
    except ContractError as exc:
        print(f"OpenAPI compatibility input error: {exc}", file=sys.stderr)
        return 2
    if findings:
        print("Incompatible OpenAPI changes detected:", file=sys.stderr)
        for finding, documents in sorted(findings.items()):
            print(f"- {finding} [documents: {', '.join(documents)}]", file=sys.stderr)
        return 1
    print(f"OpenAPI compatibility passed against protected-master SHA {args.baseline_sha}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
