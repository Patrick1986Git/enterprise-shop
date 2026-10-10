"""Independent source, compiler, executable and package contract for rebuilt Trivy."""
import importlib.util
import json
import re
import tarfile
import tempfile
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CONTRACT = ROOT / '.github/security/trivy/candidate.json'
METHOD = 'verified-upstream-source-reproducible-build-and-independent-govulncheck'
SPEC = importlib.util.spec_from_file_location('trivy_build', ROOT / 'scripts/build-trivy-candidate.py')
BUILD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUILD)
SPEC = importlib.util.spec_from_file_location('binary_readback', ROOT / 'scripts/auxiliary_binary_evidence.py')
BINARY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BINARY)
require, sha = BUILD.require, BUILD.sha
runtime_directory = BUILD.runtime_directory


def messages(data):
    text, result, cursor = data.decode(), [], 0
    decoder = json.JSONDecoder()
    while cursor < len(text):
        cursor = re.compile(r'\s*').match(text, cursor).end()
        if cursor == len(text):
            break
        message, cursor = decoder.raw_decode(text, cursor)
        require(isinstance(message, dict), 'Malformed analysis stream')
        result.append(message)
    require(result, 'Missing analysis stream')
    return result


def buildinfo(text):
    lines = text.splitlines()
    require(lines and ': go' in lines[0], 'Missing compiler build information')
    result = {'go_version': lines[0].rsplit(': go', 1)[1], 'dependencies': {}, 'build': {}}
    for line in lines[1:]:
        fields = line.strip().split('\t')
        if fields[0] == 'path':
            require(len(fields) == 2 and 'path' not in result, 'Malformed executable path')
            result['path'] = fields[1]
        elif fields[0] == 'mod':
            require(len(fields) in (3, 4) and 'main_module' not in result, 'Malformed root module')
            result['main_module'], result['main_version'] = fields[1:3]
        elif fields[0] == 'dep':
            require(len(fields) == 4 and fields[1] not in result['dependencies']
                    and fields[2].startswith('v') and fields[3].startswith('h1:'), 'Malformed embedded module')
            result['dependencies'][fields[1]] = {'version': fields[2], 'sum': fields[3]}
        elif fields[0] == 'build':
            key, value = fields[1].split('=', 1)
            require(key not in result['build'], 'Duplicate compiler setting')
            result['build'][key] = value
        else:
            raise ValueError('Unsupported/replaced embedded module')
    return result


def inventory(image, candidate):
    require(candidate['binary_sha256'] != '93f9da8e4ba5e0c1c76d8234ed2494cf9afb0a96fd21953e424bb795f3299b8e'
            and candidate['binary_sha256'] == candidate['repeat_sha256'], 'Known vulnerable/nonreproducible scanner executable')
    require(image['reference'] == candidate['image_reference']
            == 'local/enterprise-shop-trivy:sha256-' + candidate['image_id'].removeprefix('sha256:')
            and image['identity']['sha256'] == candidate['binary_sha256']
            and image['go_version'] == candidate['go_version'] == '1.27.2', 'Unreviewed scanner executable')
    require(candidate.get('exceptions') == [] and candidate.get('applicability') == []
            and image.get('exceptions', []) == [] and image.get('applicability', []) == [],
            'No scanner advisory exception or applicability waiver is authorized')
    require(candidate['builder'] == BUILD.BUILDER and candidate['builder_image_id'] == BUILD.BUILDER_ID
            and candidate['builder_manifest'] == BUILD.BUILDER_MANIFEST
            and candidate['builder_binary_sha256'] == BUILD.BUILDER_BINARY
            and candidate['sdk_sha256'] == BUILD.SDK_SHA and candidate['sdk_tools'] == BUILD.SDK_TOOLS,
            'Unexpected scanner builder/compiler')
    require(candidate['upstream_source_sha'] == BUILD.SOURCE and candidate['upstream_archive_sha256'] == BUILD.ARCHIVE_SHA
            and candidate['patch_sha256'] == BUILD.PATCH_SHA
            and candidate['go_mod_sha256'] == BUILD.GO_MOD_SHA and candidate['go_sum_sha256'] == BUILD.GO_SUM_SHA,
            'Unreviewed source or Go manifest patch')
    metadata = candidate['buildinfo']
    require(metadata['go_version'] == '1.27.2' and metadata['path'] == 'github.com/aquasecurity/trivy/cmd/trivy'
            and metadata['main_module'] == 'github.com/aquasecurity/trivy' and metadata['main_version'] == '(devel)'
            and metadata['dependencies']['golang.org/x/net'] == {
                'version': 'v0.60.0', 'sum': 'h1:79p50tfZlm0J9YfoDsSi639qSXNGVwEzOPLCxM2FsYU='},
            'Known vulnerable or wrong scanner module/compiler identity')
    require(not any(name == 'github.com/docker/docker' or name.startswith('github.com/docker/docker/')
                    for name in metadata['dependencies']), 'Legacy Docker module embedded in scanner')
    require(all(metadata['build'].get(k) == v for k, v in
                {'CGO_ENABLED': '0', 'GOARCH': 'amd64', 'GOOS': 'linux', 'GOAMD64': 'v1', '-buildmode': 'exe', '-trimpath': 'true'}.items())
            and candidate['flags'] == BUILD.FLAGS and candidate['upx'] is False
            and candidate['independent_cold_caches'] is True, 'Unreviewed scanner build settings')


def advisory_analysis(candidate, receipt, directory, metadata, source_packages):
    imported = {p['ImportPath'] for p in source_packages}
    compiled_modules = {p['Module']['Path'] for p in source_packages if p.get('Module')}
    symbols = json.loads((directory / 'binary-symbols.json').read_text())
    require(symbols['binary_sha256'] == candidate['binary_sha256'] and symbols['reader_go_version'] == 'go1.27.2'
            and symbols['function_count'] == len(symbols['functions']) > 10000
            and symbols['functions'] == sorted(symbols['functions']), 'Missing/incomplete actual executable function readback')
    require(symbols['function_count'] == candidate['binary_functions']['count']
            and sha(json.dumps(symbols['functions'], separators=(',', ':')).encode())
            == candidate['binary_functions']['names_sha256'], 'Incomplete executable function census')
    summaries = {}
    for mode in ('source', 'binary'):
        evidence = receipt['analysis'][mode]
        payload = (directory / f'{mode}-govulncheck.json').read_bytes()
        require(type(evidence['status']) is int and evidence['status'] == 0 and sha(payload) == evidence['sha256'],
                'Missing/failed independent advisory analysis')
        stream = messages(payload)
        configs = [m['config'] for m in stream if 'config' in m]
        require(len(configs) == 1 and configs[0]['scan_mode'] == mode and configs[0]['scan_level'] == 'symbol'
                and configs[0]['scanner_name'] == 'govulncheck' and configs[0]['scanner_version'] == BUILD.TOOL
                and configs[0]['db'] == 'https://vuln.go.dev', 'Self-scan/wrong advisory authority')
        now = datetime.now(timezone.utc)
        modified = datetime.fromisoformat(configs[0]['db_last_modified'].replace('Z', '+00:00'))
        require(modified <= now and (now - modified).total_seconds() < 172800, 'Stale independent Go advisory evidence')
        sboms = [m['SBOM'] for m in stream if 'SBOM' in m]
        require(len(sboms) == 1 and sboms[0]['go_version'] == 'go1.27.2'
                and sboms[0]['roots'] == [metadata['path'] if mode == 'source' else metadata['main_module']],
                'Incomplete source/binary advisory readback')
        observed = {(m['path'], m.get('version', '')) for m in sboms[0]['modules']}
        require(all((name, dep['version']) in observed for name, dep in metadata['dependencies'].items()),
                'Independent analyzer omitted compiled modules')
        osv = {m['osv']['id']: m['osv'] for m in stream if isinstance(m.get('osv'), dict)}
        findings = [m['finding'] for m in stream if 'finding' in m]
        require({f['osv'] for f in findings} == set(candidate['observed_module_notices']),
                'Unexpected independent advisory findings/notice drift')
        for finding in findings:
            trace = finding['trace']
            require(trace, 'Missing advisory trace')
            for frame in trace:
                if frame.get('function'):
                    require(frame.get('package') and
                            frame.get('receiver', '') + '.' + frame['function'] == frame['package'] + '/*',
                            'Unexpected callable Go advisory finding')
            require(finding['osv'] in osv, 'Finding missing authoritative advisory body')
            module = trace[0]['module']
            affected = [a for a in osv[finding['osv']]['affected'] if a['package']['name'] == module]
            require(affected, 'Wrong advisory module identity')
            scoped = {p['path'] for a in affected for p in a.get('ecosystem_specific', {}).get('imports', [])}
            # A source-graph/module notice is not an executable waiver: accept only
            # packages absent from the complete compiled graph, with no symbol finding.
            require((scoped and not imported.intersection(scoped)) or module not in compiled_modules,
                    'Advisory affects a compiled scanner package')
            require(scoped and not any(any(name.startswith(package + '.') or name.startswith(package + '/')
                                          for name in symbols['functions']) for package in scoped),
                    'Advisory affects an actual executable function')
        require(not any('error' in m for m in stream), 'Advisory service failure cannot be clean')
        summaries[mode] = {'module_notices': [f['osv'] for f in findings], 'callable_findings': 0,
                           'complete_compiled_module_readback': True}
    return summaries


def validate_build(candidate, directory, head, analysis=True):
    receipt = json.loads((directory / 'build-receipt.json').read_text())
    keys = ['upstream_source_sha', 'upstream_archive_sha256', 'patch_sha256', 'go_mod_sha256', 'go_sum_sha256',
            'builder', 'builder_image_id', 'builder_manifest', 'builder_binary_sha256', 'sdk_sha256', 'sdk_tools',
            'symbol_reader_sha256',
            'go_version', 'flags', 'cgo_enabled', 'upx', 'independent_cold_caches', 'binary_sha256', 'repeat_sha256',
            'image_reference', 'image_id', 'archive_sha256', 'layer_sha256', 'filesystem', 'govulncheck_version', 'govulncheck_sum']
    require(all(receipt[k] == candidate[k] for k in keys) and receipt['repository_head'] == head,
            'Scanner source/compiler/reproducibility/image evidence drift')
    require(receipt['symbol_reader_sha256'] == sha((ROOT / '.github/security/trivy/inspect-symbols.go').read_bytes()),
            'Independent binary reader source drift')
    for name, digest in receipt['evidence_hashes'].items():
        require(Path(name).name == name and sha((directory / name).read_bytes()) == digest, 'Scanner evidence file drift')
    upstream = (directory / 'upstream.tar.gz').read_bytes()
    with tempfile.TemporaryDirectory() as temporary:
        expected = BUILD.prepare(upstream, Path(temporary) / 'source')
    require(expected == receipt['source_files'] == BUILD.source_files(directory / 'source'), 'Modified upstream source bytes')
    require(sha((directory / 'source/go.mod').read_bytes()) == BUILD.GO_MOD_SHA
            and sha((directory / 'source/go.sum').read_bytes()) == BUILD.GO_SUM_SHA, 'Go manifest identity mismatch')
    first = (directory / 'trivy.first').read_bytes()
    BINARY.elf(first)
    require(sha(first) == sha((directory / 'trivy.repeat').read_bytes()) == candidate['binary_sha256'],
            'Vulnerable/modified/nonreproducible scanner bytes')
    metadata = buildinfo((directory / 'first-buildinfo.txt').read_text())
    require(metadata == candidate['buildinfo'] == buildinfo((directory / 'repeat-buildinfo.txt').read_text()),
            'Scanner compiler/module identity changed')
    BINARY.source_match(metadata, (directory / 'source/go.mod').read_text(), (directory / 'source/go.sum').read_text())
    source_packages = messages((directory / 'first-source-packages.json').read_bytes())
    names = sorted(p['ImportPath'] for p in source_packages)
    require(len(names) == len(set(names)) == candidate['source_packages']['count']
            and sha(json.dumps(names, separators=(',', ':')).encode()) == candidate['source_packages']['imports_sha256'],
            'Incomplete compiled Go package readback')
    require(any(p['ImportPath'] == metadata['path'] and p['Name'] == 'main' for p in source_packages), 'Missing scanner main package')
    observed = {(p['Module']['Path'], p['Module'].get('Version', ''), p['Module'].get('Sum', ''))
                for p in source_packages if p.get('Module') and not p['Module'].get('Main')}
    require(observed == {(m, d['version'], d['sum']) for m, d in metadata['dependencies'].items()},
            'Source/actual executable module graph disagreement')
    require(not any(p['ImportPath'].startswith('github.com/docker/docker/') for p in source_packages),
            'Legacy Docker code compiled into scanner')
    require(sha((directory / 'sdk.tar.gz').read_bytes()) == BUILD.SDK_SHA, 'Missing/wrong official Go compiler archive')
    sdk = json.loads((directory / 'compiler-sdk-receipt.json').read_text())
    require(sdk['archive_sha256'] == BUILD.SDK_SHA and sdk['byte_matches'] == BUILD.SDK_TOOLS
            and sdk['builder'] == BUILD.BUILDER, 'Compiler archive/builder byte mismatch')
    with tarfile.open(directory / 'sdk.tar.gz') as archive:
        require(all(sha(archive.extractfile('go/' + p).read()) == digest for p, digest in BUILD.SDK_TOOLS.items()),
                'Official compiler tool readback mismatch')
    for name in ('index', 'manifest'):
        raw = (directory / ('builder-' + name + '.json')).read_bytes()
        require('sha256:' + sha(raw) == (BUILD.BUILDER.split('@')[1] if name == 'index' else BUILD.BUILDER_MANIFEST),
                'Builder OCI evidence mismatch')
    first_image = BUILD.image_archive(first, (directory / 'first-ca-certificates.crt').read_bytes(), directory / 'source')
    second_image = BUILD.image_archive((directory / 'trivy.repeat').read_bytes(),
                                       (directory / 'repeat-ca-certificates.crt').read_bytes(), directory / 'source')
    require(first_image == second_image, 'Nonreproducible scratch image')
    ref, image_id, config, layer, archive, census = first_image
    require(ref == candidate['image_reference'] and image_id == candidate['image_id'] and census == candidate['filesystem']
            and config == (directory / 'image-config.json').read_bytes() and layer == (directory / 'layer.tar').read_bytes()
            and archive == (directory / 'image.tar').read_bytes(), 'Unexpected scratch image configuration/filesystem')
    summaries = advisory_analysis(candidate, receipt, directory, metadata, source_packages) if analysis else {}
    if analysis:
        require(sha((directory / 'govulncheck').read_bytes()) == receipt['tool_sha256'] == candidate['tool_sha256'],
                'Independent analyzer executable changed')
        tool = buildinfo((directory / 'govulncheck-buildinfo.txt').read_text())
        require(tool['go_version'] == '1.27.2' and tool['path'] == 'golang.org/x/vuln/cmd/govulncheck'
                and tool['main_module'] == 'golang.org/x/vuln' and tool['main_version'] == BUILD.TOOL
                and re.search(r'\tmod\tgolang.org/x/vuln\t' + re.escape(BUILD.TOOL) + r'\t' + re.escape(BUILD.TOOL_SUM),
                              (directory / 'govulncheck-buildinfo.txt').read_text()), 'Wrong independent analyzer authority')
    return metadata, receipt, summaries


def validate_upstream(directory):
    result = json.loads((directory / 'upstream.json').read_text())
    require(result['verified'] is True and result['image'] ==
            'ghcr.io/aquasecurity/trivy:0.75.0@sha256:af6acf9a6b85dfe389a1941505c0ce9efef52a4719635e1a962f022a3d855daa'
            and result['executable_sha256'] == '93f9da8e4ba5e0c1c76d8234ed2494cf9afb0a96fd21953e424bb795f3299b8e'
            and result['archive_sha256'] == 'sha256:c6e65abddb348e25f10549df887045629cf28cc72453cd1c63acb717316b3f3f'
            and result['source_commit'] == BUILD.SOURCE, 'Missing verified upstream source/release binding')
    attestations = json.loads((directory / 'attestation.json').read_text())
    require(isinstance(attestations, list) and attestations, 'Missing upstream signing evidence')
    matches = []
    for item in attestations:
        verified = item['verificationResult']
        certificate = verified['signature']['certificate']
        statement = verified['statement']
        if (certificate['issuer'] == 'https://token.actions.githubusercontent.com'
                and certificate['sourceRepositoryURI'] == 'https://github.com/aquasecurity/trivy'
                and certificate['sourceRepositoryDigest'] == BUILD.SOURCE
                and certificate['sourceRepositoryRef'] == 'refs/tags/v0.75.0'
                and certificate['runnerEnvironment'] == 'github-hosted'
                and certificate['buildSignerURI'] == 'https://github.com/aquasecurity/trivy/.github/workflows/reusable-release.yaml@refs/tags/v0.75.0'
                and statement['predicateType'] == 'https://slsa.dev/provenance/v1'
                and any(s['name'] == 'trivy_0.75.0_Linux-64bit.tar.gz' and s['digest']['sha256'] == result['archive_sha256'].removeprefix('sha256:')
                        for s in statement['subject'])):
            matches.append(item)
    require(matches, 'Wrong upstream attestation issuer/source/subject')
    return result


def validate_compatibility(directory, candidate, head, actual_images=False):
    result = json.loads((directory / 'result.json').read_text())
    require(result['verified'] is True and result['repository_head'] == head
            and result['candidate'] == candidate['image_reference']
            and result['reference_fixture_execution_authority'] is False, 'Missing/wrong scanner command evidence')
    spec = importlib.util.spec_from_file_location('compatibility', ROOT / 'scripts/verify-trivy-candidate-compatibility.py')
    compatibility = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(compatibility)
    require(result['reference_fixture'] == compatibility.REFERENCE, 'Unreviewed comparison scanner')
    metadata = directory / 'database-metadata.json'
    require(compatibility.database(metadata) == result['database'], 'Stale/manipulated compatibility database metadata')
    require(result['byte_inputs']['reference-rootfs/trivy'] == compatibility.REFERENCE_BINARY
            and result['byte_inputs']['candidate-rootfs/trivy'] == candidate['binary_sha256']
            and result['byte_inputs']['candidate.tar'] == candidate['archive_sha256'], 'Wrong compatibility executable/image bytes')
    for name in ('vulnerable', 'patched', 'malformed'):
        require(result['byte_inputs'][name + '.cdx.json'] == sha((ROOT / f'scripts/tests/fixtures/build-tool-sbom/{name}.cdx.json').read_bytes()),
                'Wrong scanner behavior fixture')
    for name, digest in result['evidence_hashes'].items():
        require(not Path(name).is_absolute() and '..' not in Path(name).parts
                and sha((directory / name).read_bytes()) == digest, 'Scanner command evidence drift')
    required = {'vulnerable', 'patched', 'reference-rootfs', 'candidate-rootfs', 'candidate-image'}
    if actual_images:
        require(len(result['actual_images']) == 3, 'Missing application/PostgreSQL/builder compatibility')
    require(result['outputs']['candidate'] == result['outputs']['reference'], 'Scanner command behavior disagreement')
    for scanner in ('reference', 'candidate'):
        outputs = result['outputs'][scanner]
        require(required <= set(outputs['cases']), 'Missing required scanner command case')
        require(set(outputs['failure_statuses']) == {'malformed', 'severity-positive', 'missing-database'}
                and all(type(s) is int and s != 0 for s in outputs['failure_statuses'].values()),
                'Incorrect scanner fail-closed command behavior')
        require(outputs['failure_statuses']['severity-positive'] == 1, 'Severity finding must return the configured exit code')
        for name, recorded in outputs['cases'].items():
            require(all(scanner + '/' + name + suffix in result['evidence_hashes']
                        for suffix in ('.raw.json', '.cdx.json', '.scan.log')), 'Incomplete scanner command receipts')
            raw = json.loads((directory / scanner / (name + '.raw.json')).read_text())
            bom = json.loads((directory / scanner / (name + '.cdx.json')).read_text())
            purls = compatibility.packages(raw)
            require(purls == set(recorded['purls']) == {p['purl'] for p in bom['components'] if 'purl' in p}
                    and sorted(compatibility.findings(raw)) == [tuple(v) for v in recorded['findings']]
                    and sorted(compatibility.findings(raw, True)) == [tuple(v) for v in recorded['blocked']],
                    'Missing/incomplete scanner command package or finding readback')
            if name in result['actual_images']:
                require(raw['Metadata']['ImageID'] == result['actual_images'][name], 'Wrong actual image compatibility input')
        cases = outputs['cases']
        require(set(map(tuple, cases['reference-rootfs']['blocked'])) == compatibility.EXPECTED
                and ('CVE-2025-48734', 'commons-beanutils:commons-beanutils', '1.9.4', 'HIGH')
                in set(map(tuple, cases['vulnerable']['blocked']))
                and not cases['patched']['blocked'] and not cases['candidate-rootfs']['blocked']
                and not cases['candidate-image']['blocked'], 'Wrong positive/negative scanner findings')
    return result


def validate(image, evidence, report, directory, head):
    candidate = json.loads(CONTRACT.read_text())
    inventory(image, candidate)
    metadata, receipt, summaries = validate_build(candidate, directory, head)
    require(evidence['image_id'] == evidence['resolved_digest'] == candidate['image_id']
            and evidence['identity_authority'] == 'local-configuration-and-archive', 'Wrong scanner image authority')
    results = report.get('Results', [])
    require(len(results) == 1 and results[0]['Target'] == 'usr/local/bin/trivy' and results[0]['Type'] == 'gobinary',
            'Missing actual scanner executable package inventory')
    expected = {(name, dep['version']) for name, dep in metadata['dependencies'].items()}
    expected |= {(metadata['main_module'], ''), ('stdlib', 'v1.27.2')}
    require({(p['Name'], p.get('Version', '')) for p in results[0]['Packages']} == expected,
            'Incomplete scanner source/executable/raw package readback')
    receipt['independent_advisory_evaluation'] = summaries
    return metadata['main_module'], receipt
