#!/usr/bin/env python3
"""Validate a JaCoCo report and print its measured line and branch baseline."""

import argparse
import json
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path


EXPECTED_PROJECT_NAME = "Enterprise Shop"


class ReportValidationError(ValueError):
    """Raised when the expected JaCoCo report is missing or invalid."""


class PolicyValidationError(ValueError):
    """Raised when the coverage policy is missing, invalid, or not satisfied."""


@dataclass(frozen=True)
class Counter:
    covered: int
    missed: int

    @property
    def total(self):
        return self.covered + self.missed


def percentage(counter):
    if counter.total == 0:
        return "0.00%"
    value = Decimal(counter.covered * 100) / Decimal(counter.total)
    return f"{value.quantize(Decimal('0.01'), rounding=ROUND_HALF_UP)}%"


def parse_counter(element, counter_type):
    matches = [counter for counter in element.findall("counter") if counter.get("type") == counter_type]
    if not matches:
        raise ReportValidationError(f"report has no project-level {counter_type} counter")
    if len(matches) != 1:
        raise ReportValidationError(f"report has multiple project-level {counter_type} counters")
    counter = matches[0]
    values = []
    for attribute in ("covered", "missed"):
        raw_value = counter.get(attribute)
        try:
            value = int(raw_value)
        except (TypeError, ValueError) as error:
            raise ReportValidationError(
                f"{counter_type} {attribute} value must be an integer"
            ) from error
        if value < 0:
            raise ReportValidationError(
                f"{counter_type} {attribute} value must be non-negative"
            )
        values.append(value)
    return Counter(covered=values[0], missed=values[1])


def validate_all_counter_values(root):
    for counter in root.iter("counter"):
        counter_type = counter.get("type", "UNKNOWN")
        for attribute in ("covered", "missed"):
            raw_value = counter.get(attribute)
            try:
                value = int(raw_value)
            except (TypeError, ValueError) as error:
                raise ReportValidationError(
                    f"{counter_type} {attribute} value must be an integer"
                ) from error
            if value < 0:
                raise ReportValidationError(
                    f"{counter_type} {attribute} value must be non-negative"
                )


def parse_report(path):
    if not path.is_file():
        raise ReportValidationError(f"report file does not exist: {path}")
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as error:
        raise ReportValidationError(f"report is malformed XML: {error}") from error
    if root.tag != "report" or root.get("name") != EXPECTED_PROJECT_NAME:
        raise ReportValidationError(
            f"report must identify the project as {EXPECTED_PROJECT_NAME!r}"
        )
    validate_all_counter_values(root)
    packages = root.findall("package")
    if not packages or not any(package.findall("class") for package in packages):
        raise ReportValidationError("report must contain packages and classes")
    if not any(package.findall("counter") for package in packages):
        raise ReportValidationError("report packages must contain counters")
    if not any(clazz.findall("counter") for package in packages for clazz in package.findall("class")):
        raise ReportValidationError("report classes must contain counters")
    lines = parse_counter(root, "LINE")
    branch_elements = [counter for counter in root.findall("counter") if counter.get("type") == "BRANCH"]
    nested_branch_data = any(
        counter.get("type") == "BRANCH" for counter in root.iter("counter")
    )
    if nested_branch_data and not branch_elements:
        raise ReportValidationError(
            "report has branch data but no project-level BRANCH counter"
        )
    branches = parse_counter(root, "BRANCH") if branch_elements else Counter(covered=0, missed=0)
    return lines, branches


def validate_companion_reports(report_directory):
    for filename in ("index.html", "jacoco.csv"):
        path = report_directory / filename
        if not path.is_file() or path.stat().st_size == 0:
            raise ReportValidationError(f"report file is missing or empty: {path}")


def parse_policy(path):
    if not path.is_file():
        raise PolicyValidationError(f"policy file does not exist: {path}")
    try:
        policy = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise PolicyValidationError(f"policy is malformed JSON: {error}") from error
    if not isinstance(policy, dict):
        raise PolicyValidationError("policy must be a JSON object")
    if policy.get("schema_version") != 1:
        raise PolicyValidationError("policy schema_version must be 1")
    metrics = policy.get("metrics")
    if not isinstance(metrics, dict):
        raise PolicyValidationError("policy metrics must be a JSON object")

    baselines = {}
    for metric in ("LINE", "BRANCH"):
        configured = metrics.get(metric)
        if not isinstance(configured, dict):
            raise PolicyValidationError(f"policy must contain a {metric} metric")
        values = []
        for field in ("covered", "missed"):
            value = configured.get(field)
            if isinstance(value, bool) or not isinstance(value, int):
                raise PolicyValidationError(
                    f"policy {metric} {field} value must be an integer"
                )
            if value < 0:
                raise PolicyValidationError(
                    f"policy {metric} {field} value must be non-negative"
                )
            values.append(value)
        baseline = Counter(covered=values[0], missed=values[1])
        if baseline.total == 0:
            raise PolicyValidationError(f"policy {metric} total must be greater than zero")
        baselines[metric] = baseline
    return baselines


def enforce_policy(lines, branches, baselines):
    failures = []
    for metric, current in (("LINE", lines), ("BRANCH", branches)):
        baseline = baselines[metric]
        left = current.covered * baseline.total
        right = baseline.covered * current.total
        if current.total == 0 or left < right:
            failures.append(
                f"{metric} coverage regression: current covered={current.covered}, "
                f"missed={current.missed}, total={current.total}, "
                f"coverage={percentage(current)}; baseline covered={baseline.covered}, "
                f"missed={baseline.missed}, total={baseline.total}, "
                f"coverage={percentage(baseline)}; exact comparison "
                f"{current.covered} * {baseline.total} = {left} >= "
                f"{baseline.covered} * {current.total} = {right} is false. "
                "Baseline changes require an explicit reviewed policy update."
            )
    if failures:
        raise PolicyValidationError("\n".join(failures))


def summary(lines, branches):
    return "\n".join(
        (
            "## JaCoCo coverage baseline",
            "",
            "| Metric | Covered | Missed | Total | Coverage |",
            "| --- | ---: | ---: | ---: | ---: |",
            f"| Lines | {lines.covered} | {lines.missed} | {lines.total} | {percentage(lines)} |",
            f"| Branches | {branches.covered} | {branches.missed} | {branches.total} | {percentage(branches)} |",
        )
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "report",
        nargs="?",
        type=Path,
        default=Path("target/site/jacoco/jacoco.xml"),
    )
    parser.add_argument("--github-summary", type=Path)
    parser.add_argument("--policy", type=Path)
    args = parser.parse_args()
    try:
        validate_companion_reports(args.report.parent)
        lines, branches = parse_report(args.report)
    except ReportValidationError as error:
        print(f"JaCoCo report validation failed: {error}", file=sys.stderr)
        return 1
    rendered = summary(lines, branches)
    print(rendered)
    if args.github_summary:
        with args.github_summary.open("a", encoding="utf-8") as output:
            output.write(rendered + "\n")
    if args.policy:
        try:
            baselines = parse_policy(args.policy)
            enforce_policy(lines, branches, baselines)
        except PolicyValidationError as error:
            print(f"JaCoCo coverage policy failed: {error}", file=sys.stderr)
            return 1
        print(f"JaCoCo coverage policy passed: {args.policy}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
