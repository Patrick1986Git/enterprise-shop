"""Verify a rebuilt Ryuk against exact reviewed source, binary and image bytes."""
import hashlib
import importlib.util
import io
import json
import re
import tarfile
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TEST_SPEC = importlib.util.spec_from_file_location('ryuk_client_tests', ROOT / 'scripts/ryuk_client_tests.py')
CLIENT_TESTS = importlib.util.module_from_spec(TEST_SPEC)
TEST_SPEC.loader.exec_module(CLIENT_TESTS)
CONTRACT = ROOT / '.github/security/ryuk/candidate.json'
spec = importlib.util.spec_from_file_location('binary_evidence', ROOT / 'scripts/auxiliary_binary_evidence.py')
BINARY = importlib.util.module_from_spec(spec)
spec.loader.exec_module(BINARY)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def messages(data):
    text, result = data.decode().strip(), []
    while text:
        message, end = json.JSONDecoder().raw_decode(text)
        result.append(message)
        text = text[end:].lstrip()
    require(result, 'Missing JSON evidence')
    return result


def inventory(image, candidate):
    require(candidate['applicability'] == [] and image.get('applicability', []) == []
            and image.get('exceptions', []) == [], 'No Ryuk applicability decision is authorized')
    require(image['reference'] == candidate['image_reference']
            == 'local/enterprise-shop-ryuk:sha256-' + candidate['image_id'].removeprefix('sha256:')
            and image['identity']['sha256'] == candidate['binary_sha256']
            and image['go_version'] == candidate['go_version'] == '1.26.8', 'Wrong candidate identity')
    require(candidate['buildinfo']['go_version'] == candidate['go_version']
            and candidate['buildinfo']['build']['CGO_ENABLED'] == '0' and candidate['upx'] is False,
            'Wrong reviewed candidate build')
    modules = candidate['buildinfo']['dependencies']
    require(modules.get('github.com/moby/moby/client', {}).get('version') == 'v0.6.1'
            and modules.get('github.com/moby/moby/api', {}).get('version') == 'v1.56.1'
            and not any(m in modules for m in ['github.com/docker/docker', 'github.com/moby/moby',
                                              'github.com/moby/moby/v2']), 'Unreviewed Docker/Moby graph')


def validate_build(candidate, directory, head, analysis=True):
    receipt = json.loads((directory / 'build-receipt.json').read_text())
    keys = ['upstream_source_sha', 'upstream_archive_sha256', 'patch_sha256', 'source_files', 'builder',
            'builder_image_id', 'go_version', 'flags', 'cgo_enabled', 'upx', 'binary_sha256', 'repeat_sha256',
            'image_reference', 'image_id', 'archive_sha256', 'layer_sha256', 'filesystem', 'govulncheck_version', 'govulncheck_sum']
    require(all(receipt[k] == candidate[k] for k in keys) and receipt['repository_head'] == head,
            'Candidate source/build/image identity drift')
    require(candidate.get('applicability') == [], 'No candidate applicability is authorized')
    for name in ['buildinfo.txt', 'source-packages.json', 'source-modules.json']:
        require(receipt['evidence_hashes'][name] == sha((directory / name).read_bytes()), 'Candidate evidence file drift')
    require(sha((directory / 'upstream.tar.gz').read_bytes()) == candidate['upstream_archive_sha256'],
            'Wrong source archive bytes')
    actual_source = {p.relative_to(directory / 'source').as_posix(): sha(p.read_bytes())
                     for p in sorted((directory / 'source').rglob('*')) if p.is_file()}
    require(actual_source == candidate['source_files'], 'Candidate source files drift')
    CLIENT_TESTS.validate(directory / 'tests', receipt['client_tests'], sha)
    binary = (directory / 'ryuk').read_bytes()
    BINARY.elf(binary)
    require(sha(binary) == sha((directory / 'ryuk.repeat').read_bytes()) == candidate['binary_sha256'],
            'Candidate binary drift or nonrepeatable build')
    metadata = BINARY.buildinfo((directory / 'buildinfo.txt').read_text())
    require(metadata == candidate['buildinfo'], 'Source/binary dependency mismatch')
    BINARY.source_match(metadata, (directory / 'source/go.mod').read_text(), (directory / 'source/go.sum').read_text())
    require('sha256:' + sha((directory / 'image-config.json').read_bytes()) == candidate['image_id'],
            'Candidate image configuration drift')
    archive = (directory / 'image.tar').read_bytes()
    require(sha(archive) == candidate['archive_sha256'], 'Candidate image archive drift')
    with tarfile.open(fileobj=io.BytesIO(archive)) as saved:
        entries = json.load(saved.extractfile('manifest.json'))
        require(len(entries) == 1 and entries[0]['RepoTags'] == [candidate['image_reference']]
                and len(entries[0]['Layers']) == 1, 'Wrong candidate archive identity')
        config = saved.extractfile(entries[0]['Config']).read()
        require(config == (directory / 'image-config.json').read_bytes(), 'Archive configuration mismatch')
        layer = saved.extractfile(entries[0]['Layers'][0]).read()
    require(sha(layer) == candidate['layer_sha256']
            and json.loads(config)['rootfs']['diff_ids'] == ['sha256:' + sha(layer)], 'Candidate layer drift')
    census = {}
    with tarfile.open(fileobj=io.BytesIO(layer)) as filesystem:
        for member in filesystem:
            require(member.isfile() and member.name not in census, 'Unexpected candidate filesystem member')
            data = filesystem.extractfile(member).read()
            census[member.name] = {'sha256': sha(data), 'size': len(data)}
    require(census == candidate['filesystem']
            and set(census) == {'bin/ryuk', 'etc/ssl/certs/ca-certificates.crt'}, 'Candidate scratch census drift')
    packages = messages((directory / 'source-packages.json').read_bytes())
    require(any(p['ImportPath'] == metadata['main_module'] and p['Name'] == 'main' for p in packages),
            'Missing candidate main source package')
    require(not any(p['ImportPath'].startswith(('github.com/docker/docker/', 'github.com/moby/moby/v2/',
                                               'github.com/moby/moby/daemon')) for p in packages),
            'Legacy/daemon package is reachable from candidate')
    observed = {(p['Module']['Path'], p['Module'].get('Version', ''), p['Module'].get('Sum', ''))
                for p in packages if p.get('Module') and not p['Module'].get('Main')}
    expected = {(m, d['version'], d['sum']) for m, d in metadata['dependencies'].items()}
    require(observed == expected, 'Source/binary dependency mismatch')
    if analysis:
        for mode in ['source', 'binary']:
            evidence = receipt['analysis'][mode]
            data = (directory / f'{mode}-govulncheck.json').read_bytes()
            require(type(evidence['status']) is int and evidence['status'] == 0
                    and sha(data) == evidence['sha256'], 'Missing/failed source or binary govulncheck evidence')
            stream = messages(data)
            configs = [m['config'] for m in stream if 'config' in m]
            require(len(configs) == 1 and configs[0]['scan_mode'] == mode and configs[0]['scan_level'] == 'symbol'
                    and configs[0]['scanner_name'] == 'govulncheck'
                    and configs[0]['scanner_version'] == candidate['govulncheck_version']
                    and configs[0]['db'] == 'https://vuln.go.dev', 'Wrong candidate govulncheck authority')
            now = datetime.now(timezone.utc)
            modified = datetime.fromisoformat(configs[0]['db_last_modified'].replace('Z', '+00:00'))
            require(modified <= now and (now - modified).total_seconds() < 172800, 'Stale Go advisory evidence')
            sboms = [m['SBOM'] for m in stream if 'SBOM' in m]
            require(len(sboms) == 1 and sboms[0]['go_version'] == 'go' + candidate['go_version']
                    and sboms[0]['roots'] == [metadata['main_module']], 'Wrong source/binary Go identity')
            modules = {(m['path'], m.get('version', '')) for m in sboms[0]['modules']}
            require(all((m, d['version']) in modules for m, d in metadata['dependencies'].items())
                    and not any(m in {'github.com/docker/docker', 'github.com/moby/moby', 'github.com/moby/moby/v2'}
                                for m, _ in modules), 'Wrong source/binary govulncheck dependency graph')
            require(not any('finding' in m for m in stream), 'Candidate has Go advisory findings')
        require(receipt['tool_sha256'] == sha((directory / 'govulncheck').read_bytes()), 'Analyzer binary identity mismatch')
        require(receipt['evidence_hashes']['govulncheck-buildinfo.txt']
                == sha((directory / 'govulncheck-buildinfo.txt').read_bytes()), 'Analyzer metadata drift')
        tool_metadata = (directory / 'govulncheck-buildinfo.txt').read_text()
        require(tool_metadata.splitlines()[0].endswith(': go' + candidate['go_version'])
                and '\tpath\tgolang.org/x/vuln/cmd/govulncheck\n' in tool_metadata
                and re.search(r'\tmod\tgolang.org/x/vuln\t' + re.escape(candidate['govulncheck_version'])
                              + r'\t' + re.escape(candidate['govulncheck_sum']), tool_metadata), 'Wrong installed analyzer')
    return metadata, receipt


def validate(image, evidence, report, directory, head):
    candidate = json.loads(CONTRACT.read_text())
    inventory(image, candidate)
    metadata, receipt = validate_build(candidate, directory, head)
    require(evidence['image_id'] == evidence['resolved_digest'] == candidate['image_id']
            and evidence['identity_authority'] == 'local-configuration-and-archive', 'Wrong local candidate image authority')
    results = report.get('Results', [])
    require(len(results) == 1 and results[0]['Target'] == 'bin/ryuk' and results[0]['Type'] == 'gobinary',
            'Missing direct candidate executable package evidence')
    expected = {(m, d['version']) for m, d in metadata['dependencies'].items()}
    expected |= {(metadata['main_module'], ''), ('stdlib', 'v' + metadata['go_version'])}
    require({(p['Name'], p.get('Version', '')) for p in results[0]['Packages']} == expected,
            'Candidate raw/source/binary package mismatch')
    return metadata['main_module'], receipt
