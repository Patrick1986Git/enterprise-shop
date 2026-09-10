#!/usr/bin/env python3
"""Validate the operator contract against Spring placeholders in application-prod.yml."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[1]
PRODUCTION_CONFIG = ROOT / "src/main/resources/application-prod.yml"
CONTRACT = ROOT / "docs/operations/production-configuration.md"
ENVIRONMENT_NAME = re.compile(r"[A-Z][A-Z0-9_]*\Z")
CONTRACT_ROW = re.compile(r"^\| `([A-Z][A-Z0-9_]*)` \| `([^`]+)` \| (required|defaulted|empty-default) \|")


class ContractError(ValueError):
    """Raised when configuration cannot be checked without ambiguity."""


@dataclass(frozen=True)
class Placeholder:
    variable: str
    property_path: str
    syntax: str
    default: str | None


def _split_expression(expression: str) -> tuple[str, str | None]:
    depth = 0
    for index, character in enumerate(expression):
        if expression.startswith("${", index):
            depth += 1
        elif character == "}" and depth:
            depth -= 1
        elif character == ":" and depth == 0:
            return expression[:index], expression[index + 1:]
    return expression, None


def _expressions(value: str) -> list[str]:
    expressions: list[str] = []
    index = 0
    while index < len(value):
        start = value.find("${", index)
        if start < 0:
            if "}" in value[index:]:
                raise ContractError("unmatched closing placeholder brace")
            break
        depth = 1
        cursor = start + 2
        while cursor < len(value) and depth:
            if value.startswith("${", cursor):
                depth += 1
                cursor += 2
                continue
            if value[cursor] == "}":
                depth -= 1
            cursor += 1
        if depth:
            raise ContractError("unterminated Spring placeholder")
        expression = value[start + 2:cursor - 1]
        expressions.append(expression)
        _, default = _split_expression(expression)
        if default is not None and "${" in default:
            expressions.extend(_expressions(default))
        index = cursor
    return expressions


def extract_placeholders(contents: str) -> list[Placeholder]:
    """Extract placeholders with indentation-derived Spring property paths."""
    result: list[Placeholder] = []
    parents: list[tuple[int, str]] = []
    for line_number, raw_line in enumerate(contents.splitlines(), 1):
        if not raw_line.strip() or raw_line.lstrip().startswith("#"):
            continue
        indentation = len(raw_line) - len(raw_line.lstrip(" "))
        if "\t" in raw_line[:indentation]:
            raise ContractError(f"line {line_number}: tabs make the property path ambiguous")
        match = re.match(r"\s*([^:#][^:]*):(?:\s*(.*))?$", raw_line)
        if not match:
            if "${" in raw_line:
                raise ContractError(f"line {line_number}: placeholder is not in a supported mapping value")
            continue
        key, value = match.group(1).strip(), match.group(2)
        while parents and parents[-1][0] >= indentation:
            parents.pop()
        property_path = ".".join([entry[1] for entry in parents] + [key])
        if value is None or not value.strip():
            parents.append((indentation, key))
            continue
        for expression in _expressions(value.split(" #", 1)[0]):
            variable, default = _split_expression(expression)
            if not ENVIRONMENT_NAME.fullmatch(variable):
                raise ContractError(f"line {line_number}: unsupported placeholder name {variable!r}")
            syntax = "required" if default is None else ("empty-default" if default == "" else "defaulted")
            result.append(Placeholder(variable, property_path, syntax, default))
    return result


def parse_contract(contents: str) -> dict[str, tuple[str, str]]:
    inventory: dict[str, tuple[str, str]] = {}
    for line_number, line in enumerate(contents.splitlines(), 1):
        match = CONTRACT_ROW.match(line)
        if not match:
            continue
        variable, property_path, syntax = match.groups()
        if variable in inventory:
            raise ContractError(f"line {line_number}: duplicate contract row for {variable}")
        inventory[variable] = (property_path, syntax)
    if not inventory:
        raise ContractError("canonical contract contains no inventory rows")
    return inventory


def validate(config_contents: str, contract_contents: str) -> None:
    placeholders = extract_placeholders(config_contents)
    contract = parse_contract(contract_contents)
    by_variable: dict[str, list[Placeholder]] = {}
    for placeholder in placeholders:
        by_variable.setdefault(placeholder.variable, []).append(placeholder)
    configured, documented = set(by_variable), set(contract)
    errors: list[str] = []
    for variable in sorted(configured - documented):
        errors.append(f"undocumented production variable: {variable}")
    for variable in sorted(documented - configured):
        errors.append(f"stale production contract variable: {variable}")
    for variable in sorted(configured & documented):
        occurrences = by_variable[variable]
        expected_path, expected_syntax = contract[variable]
        actual_paths = ", ".join(dict.fromkeys(item.property_path for item in occurrences))
        actual_syntax = "required" if any(item.syntax == "required" for item in occurrences) else occurrences[0].syntax
        if expected_path != actual_paths:
            errors.append(f"{variable}: property path is {actual_paths!r}, contract says {expected_path!r}")
        if expected_syntax != actual_syntax:
            errors.append(f"{variable}: syntax is {actual_syntax}, contract says {expected_syntax}")
    if errors:
        raise ContractError("\n".join(errors))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, default=PRODUCTION_CONFIG)
    parser.add_argument("--contract", type=Path, default=CONTRACT)
    args = parser.parse_args()
    try:
        validate(args.config.read_text(encoding="utf-8"), args.contract.read_text(encoding="utf-8"))
    except (OSError, ContractError) as error:
        print(f"Production configuration contract failed: {error}", file=sys.stderr)
        return 1
    print(f"Production configuration contract passed: {args.contract}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
