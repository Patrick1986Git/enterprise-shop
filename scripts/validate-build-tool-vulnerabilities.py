#!/usr/bin/env python3
"""Validate complete pinned-Trivy Maven SBOM results and block HIGH/CRITICAL findings."""
import argparse
import json
import sys
from pathlib import Path


class EvidenceError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise EvidenceError(message)


def read_json(path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, 'Duplicate JSON key')
            result[key] = value
        return result
    return json.loads(Path(path).read_text(), object_pairs_hook=unique)


def validate(document, report, scanner_exit_status):
    require(isinstance(document, dict) and isinstance(report, dict), 'Malformed scanner input/report')
    require(type(scanner_exit_status) is int and scanner_exit_status == 0,
            'Scanner/database failure cannot be a clean result')
    require(type(report.get('SchemaVersion')) is int and report.get('SchemaVersion') == 2
            and report.get('ArtifactType') == 'cyclonedx'
            and report.get('Trivy', {}).get('Version') == '0.72.0', 'Unexpected scanner provenance')
    expected = {x['purl']: (x['group'] + ':' + x['name'], x['version'])
                for x in document['components']}
    require(expected and len(expected) == len(document['components']), 'Empty/duplicate input components')
    results = report.get('Results')
    require(isinstance(results, list) and len(results) == 1, 'Missing/unexpected Maven scan result')
    result = results[0]
    require(result.get('Class') == 'lang-pkgs' and result.get('Type') == 'jar',
            'Scanner did not interpret Maven packages')
    packages = result.get('Packages')
    require(isinstance(packages, list) and packages, 'Scanner package readback is missing')
    observed = {}
    for package in packages:
        identity = package.get('Identifier', {}).get('PURL')
        require(identity in expected and identity not in observed, 'Unknown/duplicate scanner package')
        value = (package.get('Name'), package.get('Version'))
        require(value == expected[identity], 'Scanner changed Maven identity/version')
        observed[identity] = value
    require(observed == expected, 'Scanner silently omitted input Maven components')
    vulnerabilities = result.get('Vulnerabilities', [])
    if vulnerabilities is None:
        vulnerabilities = []
    require(isinstance(vulnerabilities, list), 'Malformed vulnerability evidence')
    blocked = []
    for vulnerability in vulnerabilities:
        identity = vulnerability.get('PkgIdentifier', {}).get('PURL')
        require(identity in expected and
                (vulnerability.get('PkgName'), vulnerability.get('InstalledVersion')) == expected[identity],
                'Vulnerability identity is not an inventoried component')
        severity = vulnerability.get('Severity')
        require(severity in {'UNKNOWN', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL'}
                and isinstance(vulnerability.get('VulnerabilityID'), str)
                and vulnerability['VulnerabilityID'], 'Malformed vulnerability severity/identity')
        if severity in {'HIGH', 'CRITICAL'}:
            blocked.append((identity, vulnerability['VulnerabilityID'], severity))
    return sorted(set(blocked))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bom', required=True)
    parser.add_argument('--report', required=True)
    parser.add_argument('--scanner-exit-status', type=int, required=True)
    args = parser.parse_args()
    try:
        blocked = validate(read_json(args.bom), read_json(args.report), args.scanner_exit_status)
        for identity, advisory, severity in blocked:
            print(f'{severity}: {identity}: {advisory}', file=sys.stderr)
        if blocked:
            print(f'Build-tool policy blocked {len(blocked)} HIGH/CRITICAL matches.', file=sys.stderr)
            return 1
        print('Complete build-tool Maven package readback; no HIGH/CRITICAL findings.')
        return 0
    except (OSError, ValueError, KeyError, TypeError, AttributeError) as error:
        print(f'Invalid build-tool vulnerability evidence: {error}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
