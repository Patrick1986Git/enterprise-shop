#!/usr/bin/env python3
"""Run the existing pinned Trivy authority with package readback and isolated fixtures."""
import argparse
import importlib.util
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TRIVY_IMAGE = 'ghcr.io/aquasecurity/trivy:0.72.0@sha256:cffe3f5161a47a6823fbd23d985795b3ed72a4c806da4c4df16266c02accdd6f'


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


INVENTORY = module('build_tool_inventory', ROOT / 'scripts/build-tool-inventory.py')
POLICY = module('build_tool_policy', ROOT / 'scripts/validate-build-tool-vulnerabilities.py')


def docker(cache, directory):
    cache.mkdir(parents=True, exist_ok=True)
    command = ['docker', 'run', '--rm', '--platform', 'linux/amd64',
               '-v', f'{cache.resolve()}:/root/.cache/trivy',
               '-v', f'{ROOT}:/workspace:ro', '-v', f'{directory.resolve()}:/evidence',
               '-w', '/workspace']
    # Local managed runtimes can supply a public CA bundle; hosted Actions use
    # the pinned image's trust store. This is not a TLS verification bypass.
    certificate = os.environ.get('BUILD_TOOL_SCAN_CA_BUNDLE')
    if certificate:
        command += ['-v', f'{Path(certificate).resolve()}:/run/scan-ca.pem:ro',
                    '-e', 'SSL_CERT_FILE=/run/scan-ca.pem']
    return command + [TRIVY_IMAGE]


def scan(command, source, target, log):
    target.unlink(missing_ok=True)
    with log.open('w') as stream:
        result = subprocess.run(command + ['sbom', '--skip-db-update', '--ignorefile', '/dev/null',
                                          '--list-all-pkgs', '--exit-code', '0', '--format', 'json',
                                          '--output', f'/evidence/{target.name}', source],
                                stdout=stream, stderr=subprocess.STDOUT)
    return result.returncode


def fixtures(command, directory):
    result = {}
    for name, version in [('vulnerable', '1.9.4'), ('patched', '1.11.0')]:
        source = ROOT / f'scripts/tests/fixtures/build-tool-sbom/{name}.cdx.json'
        target = directory / f'{name}.trivy.json'
        status = scan(command, f'/workspace/{source.relative_to(ROOT)}', target,
                      directory / f'{name}.scan.log')
        if status:
            raise ValueError(f'{name} fixture scanner/database failure: {status}')
        findings = POLICY.validate(INVENTORY.read_json(source), INVENTORY.read_json(target), status)
        expected = [(f'pkg:maven/commons-beanutils/commons-beanutils@{version}', 'CVE-2025-48734', 'HIGH')]
        if (name == 'vulnerable' and expected[0] not in findings) or (name == 'patched' and findings):
            raise ValueError(f'{name} fixture advisory/severity/policy mismatch: {findings}')
        result[name] = {'scanner_exit_status': status, 'blocked': findings}
    target = directory / 'malformed.trivy.json'
    status = scan(command, '/workspace/scripts/tests/fixtures/build-tool-sbom/malformed.cdx.json',
                  target, directory / 'malformed.scan.log')
    if not status:
        raise ValueError('Trivy accepted malformed CycloneDX')
    result['malformed'] = {'scanner_exit_status': status}
    empty_cache = directory / 'empty-db-cache'
    if empty_cache.exists():
        shutil.rmtree(empty_cache)
    failing = docker(empty_cache, directory)
    target = directory / 'missing-database.trivy.json'
    status = scan(failing, '/workspace/scripts/tests/fixtures/build-tool-sbom/patched.cdx.json',
                  target, directory / 'missing-database.scan.log')
    if not status:
        raise ValueError('Missing vulnerability database was reported as clean')
    result['missing-database'] = {'scanner_exit_status': status}
    INVENTORY.write_json(directory / 'scanner-fixtures.json', result)
    print('Pinned Trivy vulnerable/patched/malformed/database fixtures passed.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', default='.tmp/build-tool-security')
    parser.add_argument('--cache', default='.tmp/container-security/trivy-cache')
    args = parser.parse_args()
    try:
        directory = Path(args.directory).resolve()
        directory.mkdir(parents=True, exist_ok=True)
        for name in ('policy-result.json', 'scanner-fixtures.json',
                     'enterprise-shop-build-tools.trivy.json'):
            (directory / name).unlink(missing_ok=True)
        source = directory / 'enterprise-shop-build-tools.cdx.json'
        document = INVENTORY.read_json(source)
        INVENTORY.validate_bom(document, INVENTORY.read_json(INVENTORY.CONTRACT))
        command = docker(Path(args.cache).resolve(), directory)
        download = command + ['image', '--download-db-only']
        if os.environ.get('TRIVY_DB_REPOSITORY'):
            download += ['--db-repository', os.environ['TRIVY_DB_REPOSITORY']]
        with (directory / 'database-update.log').open('w') as log:
            subprocess.run(download, stdout=log, stderr=subprocess.STDOUT, check=True)
        metadata = Path(args.cache) / 'db/metadata.json'
        INVENTORY.write_json(directory / 'database-metadata.json', INVENTORY.read_json(metadata))
        fixtures(command, directory)
        target = directory / 'enterprise-shop-build-tools.trivy.json'
        status = scan(command, f'/evidence/{source.name}', target, directory / 'build-tools.scan.log')
        if status:
            raise ValueError(f'Build-tool scanner/database failure: {status}')
        findings = POLICY.validate(document, INVENTORY.read_json(target), status)
        INVENTORY.write_json(directory / 'policy-result.json', {'scanner_exit_status': status,
                             'threshold': ['HIGH', 'CRITICAL'], 'blocked': findings})
        for identity, advisory, severity in findings:
            print(f'{severity}: {identity}: {advisory}', file=sys.stderr)
        return 1 if findings else 0
    except (OSError, ValueError, KeyError, TypeError, subprocess.CalledProcessError) as error:
        print(f'Build-tool scan failed: {error}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
