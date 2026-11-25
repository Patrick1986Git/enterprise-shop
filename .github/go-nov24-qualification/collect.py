"""Evidence collection for the isolated November 24 source tree."""
from pathlib import Path
import gzip
import hashlib
import importlib.util
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import time
import urllib.request
import uuid

from accounting import ROOT, GIB, REQUIRED_EVENTS, fixture, inspect, now, output, owned_slice, require, run, write
import runner_swap


TREE = 'fdb28e42bed4ae3066bd87cb83531c3772a49b53'
CANDIDATE = '8663f7dfddaddd58d238727e7198538f8c651ecf'
PREVIOUS_HARNESS = '73f8e10d32a83aab127e0fc352e14cab429e124c'
BRANCH = 'refs/heads/evidence/go-nov24-docker29-6285a517'
REPOSITORY = 'Patrick1986Git/enterprise-shop'
DB_FLOOR = '2026-11-24T15:56:00Z'
RYUK_SDK_SHA = '9849f4e04ee35303714e34fbc3ac8ab44ea3e448a95aa182f91e3b9b79c710ec'
RYUK_SDK_TOOLS = {
    'bin/go': '95499375c24b610cdc9e3684cec920174ec4c4f3f66d35b4332107f0f7d3d27d',
    'bin/gofmt': 'cae643e8d7891e50a4088102d84e157010d789b5292b89e87be488ccbcc0f2e5',
    'pkg/tool/linux_amd64/compile': '896a17923b901c9581505c08e8ed1c5a2a6b1b519beb457ceb106f1b59373329',
    'pkg/tool/linux_amd64/link': 'f2e750ca33de93eae493abef851eec050e368c6840f18d1fb03a73b7a696e928',
}


class MeasuredFailure(ValueError):
    """An observed result violates a qualification criterion."""


def load(script, name):
    spec = importlib.util.spec_from_file_location(name, script)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def metadata(directory, authority):
    """Bracket the compressed endpoint with original uncompressed responses."""
    plain = authority.acquire(directory, 'source', 'before')
    url = authority.DATABASE + '/index/db.json.gz'
    started = now()
    request = urllib.request.Request(url, headers={'Cache-Control': 'no-cache', 'Pragma': 'no-cache'})
    with urllib.request.build_opener(authority.NoRedirect()).open(request, timeout=20) as response:
        require(response.status == 200 and response.geturl() == url, 'Wrong compressed Go authority')
        raw = response.read(4097)
    completed = now()
    require(len(raw) <= 4096, 'Oversized compressed Go metadata')
    path = directory / 'db.json.gz'
    path.write_bytes(raw)
    with gzip.open(path, 'rb') as stream:
        compressed = authority.read_metadata(stream.read(4097))
    after = authority.acquire(directory, 'source', 'after')
    revision = authority.read_metadata((directory / 'source-db-before.json').read_bytes())
    final_revision = authority.read_metadata((directory / 'source-db-after.json').read_bytes())
    write(directory / 'metadata.json', {'uncompressed_before': plain, 'uncompressed_after': after,
          'compressed': {'url': url, 'sha256': sha(path),
          'status': 200, 'started_at': started, 'completed_at': completed},
          'uncompressed_revision': revision.isoformat(), 'uncompressed_after_revision': final_revision.isoformat(),
          'compressed_revision': compressed.isoformat()})
    require(revision == compressed == final_revision and authority.timestamp(DB_FLOOR) <= revision
            and authority.timestamp(plain['completed_at']) <= authority.timestamp(started)
            <= authority.timestamp(completed) <= authority.timestamp(after['started_at']),
            'Official Go database views disagree or regressed')


def host_resources(directory):
    version = json.loads(output('docker', 'version', '--format', '{{json .}}'))
    selected = ('ServerVersion', 'CgroupVersion', 'CgroupDriver', 'Architecture', 'NCPU', 'MemTotal', 'Driver')
    config = json.loads(output('docker', 'info', '--format', '{{json .}}'))
    buildx = output('docker', 'buildx', 'version')
    builder = output('docker', 'buildx', 'inspect', 'default')
    resources = runner_swap.memory()
    mount = output('findmnt', '-T', str(ROOT), '-no', 'FSTYPE,OPTIONS')
    process_cgroup = Path('/proc/self/cgroup').read_text().strip()
    limits = {}
    if process_cgroup.startswith('0::/'):
        current = ROOT / process_cgroup[4:]
        for path in (current, *current.parents):
            if not path.is_relative_to(ROOT):
                break
            limits[str(path)] = {f: (path / f).read_text().strip() for f in ('memory.max', 'cpu.max', 'pids.max')
                                 if (path / f).exists()}
    write(directory / 'runner-capabilities.json', {'docker_version': version, 'docker_info': {k: config[k] for k in selected},
          'buildx': buildx, 'buildkit': builder, 'platform': list(os.uname()), 'cgroup_mount': mount,
          'process_cgroup': process_cgroup, 'cgroup_limits': limits, 'affinity': sorted(os.sched_getaffinity(0)),
          'memory_bytes': resources, 'evidence_disk_free_bytes': shutil.disk_usage(directory).free,
          'runner_image': {k: os.environ.get(k) for k in ('ImageOS', 'ImageVersion', 'RUNNER_ARCH', 'RUNNER_OS')}})
    buildkit_version = re.search(r'^BuildKit( version)?:\s*(v?\d+\.\d+\.\d+)', builder, re.M | re.I)
    print('Runner capabilities ' + json.dumps({
          'docker_client': version['Client']['Version'], 'docker_server': version['Server']['Version'],
          'buildkit': buildkit_version[2] if buildkit_version else None,
          'buildx': next(iter(re.findall(r'\bv\d+\.\d+\.\d+\b', buildx)), None),
          'platform': version['Server']['Os'] + '/' + version['Server']['Arch'],
          'cgroup': str(config['CgroupVersion']) + '/' + config['CgroupDriver'],
          'cpus': len(os.sched_getaffinity(0)), 'image': os.environ.get('ImageVersion'),
          'evidence_disk_free_bytes': shutil.disk_usage(directory).free, 'memory_bytes': resources}), flush=True)
    require(all(v['Version'].split('.')[0] == '29' and v['Arch'] == 'amd64' and v['Os'] == 'linux'
                for v in (version['Server'], version['Client'])), 'Required Docker 29 linux/amd64 unavailable')
    require(buildkit_version, 'BuildKit version unavailable')
    require(str(config['CgroupVersion']) == '2' and config['CgroupDriver'] == 'systemd'
            and mount.startswith('cgroup2 ') and 'memory_localevents' not in mount,
            'Systemd cgroup v2 hierarchical accounting unavailable')
    require(len(os.sched_getaffinity(0)) >= 2, 'Runner requires at least two available CPUs')
    require(resources['MemAvailable'] >= 3 * GIB, 'Runner requires at least 3 GiB available RAM')
    run(['sudo', '-n', 'true'])
    if resources['SwapFree'] < runner_swap.MIN_FREE:
        resources = runner_swap.provision(directory, resources)
        print('Temporary swap provisioning PASS ' + json.dumps(resources), flush=True)
    require(resources['MemAvailable'] >= 3 * GIB, 'Runner requires at least 3 GiB available RAM after swap provisioning')
    require(resources['SwapFree'] >= 2 * GIB, 'Runner requires at least 2 GiB free host swap')


def connectivity(directory):
    endpoints = ('https://go.dev/dl/go1.27.4.linux-amd64.tar.gz', 'https://go.dev/dl/go1.26.11.linux-amd64.tar.gz',
                 'https://proxy.golang.org/github.com/klauspost/compress/@v/v1.20.2.mod',
                 'https://sum.golang.org/lookup/github.com/klauspost/compress@v1.20.2')
    values = []
    try:
        for url in endpoints:
            record = {'url': url, 'started_at': now()}
            values.append(record)
            try:
                with urllib.request.urlopen(urllib.request.Request(url, method='HEAD'), timeout=20) as response:
                    record.update(resolved_url=response.geturl(), status=response.status, completed_at=now())
                    require(response.status == 200 and response.geturl().startswith('https://'), 'Official endpoint unavailable')
            except OSError as error:
                record.update(error=str(error), completed_at=now())
                raise
    finally:
        write(directory / 'connectivity.json', values)


def preflight(directory, builder, checks):
    checks['prerequisite'] = 'runner including available RAM and active host swap'
    host_resources(directory)
    checks['prerequisite'] = 'official Go database consistency and freshness'
    metadata(directory, builder.RYUK.ADVISORY)
    checks['prerequisite'] = 'official Go SDK, dependency proxy and checksum connectivity'
    connectivity(directory)
    checks['prerequisite'] = 'reviewed Docker builder image acquisition'
    run(['docker', 'pull', '--platform', 'linux/amd64', builder.BUILDER], timeout=150)
    for mode in ('late', 'oom'):
        checks['prerequisite'] = 'terminal cgroup ' + mode + ' fixture'
        fixture(directory, builder.BUILDER, mode)
        checks['lifecycle'][mode] = 'PASS'
    checks['prerequisite'] = 'complete'


def verify_stream(authority, module, directory, mode, receipt):
    stream = module.messages((directory / (mode + '-govulncheck.json')).read_bytes())
    configs = [entry['config'] for entry in stream if 'config' in entry]
    require(len(configs) == 1 and configs[0]['scan_mode'] == mode and configs[0]['scan_level'] == 'symbol'
            and configs[0]['scanner_name'] == 'govulncheck' and configs[0]['scanner_version'] == 'v1.8.0',
            'Incomplete independent symbol analysis')
    authority.validate(directory, mode, receipt, configs[0])
    sbom = [entry['SBOM'] for entry in stream if 'SBOM' in entry]
    require(len(sbom) == 1 and not any('error' in entry for entry in stream),
            'Incomplete advisory evidence')
    findings = sorted({entry['finding']['osv'] for entry in stream if 'finding' in entry})
    return {'db_revision': configs[0]['db_last_modified'], 'findings': findings, 'report': receipt}, sbom[0], stream


def verify_graph(sbom, metadata, mode):
    root = metadata['path'] if mode == 'source' else metadata['main_module']
    require(sbom['go_version'] == 'go' + metadata['go_version'] and sbom['roots'] == [root],
            'Wrong compiler or root package in independent advisory SBOM')
    selected = {(m['path'], m.get('version', '')) for m in sbom['modules']}
    require(all((name, dep['version']) in selected for name, dep in metadata['dependencies'].items()),
            'Independent advisory SBOM omitted compiled modules')


def package_graph(verifier, directory, name, metadata):
    packages = verifier.messages((directory / name).read_bytes())
    imports = sorted(p['ImportPath'] for p in packages)
    graph = {(m['Path'], m['Version'], m['Sum']) for m in (p.get('Module') for p in packages) if m and not m.get('Main')}
    require(len(imports) == len(set(imports)) and any(p['ImportPath'] == metadata['path'] and p['Name'] == 'main'
                                                     for p in packages), 'Incomplete compiled package census')
    require(graph == {(m, d['version'], d['sum']) for m, d in metadata['dependencies'].items()},
            'Compiled source and executable module graph differ')
    return imports


def selected_graph(verifier, directory, name, metadata):
    modules = verifier.messages((directory / name).read_bytes())
    root = [m['Path'] for m in modules if m.get('Main')]
    entries = {m['Path']: (m['Version'], m.get('Sum'), m.get('GoModSum')) for m in modules if not m.get('Main')}
    require(root == [metadata['main_module']] and len(entries) == len(modules) - 1
            and not any('Replace' in m or 'Error' in m for m in modules), 'Invalid selected Go module graph')
    require(all(entries.get(name, ())[:2] == (dep['version'], dep['sum']) for name, dep in metadata['dependencies'].items()),
            'Selected and embedded module identities differ')
    return entries


def trivy_advisory(verifier, authority, directory, mode, receipt, metadata, imports, functions):
    report, sbom, stream = verify_stream(authority, verifier, directory, mode, receipt)
    verify_graph(sbom, metadata, mode)
    advisories = {entry['osv']['id']: entry['osv'] for entry in stream if 'osv' in entry}
    for entry in stream:
        if 'finding' not in entry:
            continue
        finding = entry['finding']
        trace = finding['trace']
        if finding['osv'] != 'GO-2026-5932' or not trace or trace[0]['module'] != 'golang.org/x/crypto':
            raise MeasuredFailure('Unexpected Trivy Go advisory finding ' + finding['osv'])
        for frame in trace:
            if frame.get('function') and frame.get('receiver', '') + '.' + frame['function'] != frame.get('package', '') + '/*':
                raise MeasuredFailure('Callable Go advisory finding ' + finding['osv'])
        affected = [record for record in advisories[finding['osv']]['affected']
                    if record['package']['name'] == trace[0]['module']]
        scopes = {item['path'] for record in affected for item in record.get('ecosystem_specific', {}).get('imports', [])}
        if not scopes or set(imports).intersection(scopes) or any(
                name.startswith(scope + '.') or name.startswith(scope + '/') for scope in scopes for name in functions):
            raise MeasuredFailure('Go advisory affects the compiled Trivy package or function census')
    report['callable_findings'] = 0
    report['compiled_graph'] = 'complete'
    return report


def terminal_analysis(builder, verify, original, analyzer, args, directory, head):
    diagnostic = {'status': 'BLOCKED', 'started_at': now()}
    result = None
    try:
        require(not any(a.startswith('--cgroup-parent') for a in analyzer), 'Unexpected source cgroup override')
        require(args == ['-mode=source', '-json', './cmd/trivy'], 'Unexpected source command')
        with owned_slice(directory, diagnostic) as group:
            result = original(analyzer[:3] + ['--cgroup-parent', group.name] + analyzer[3:], args, directory, head)
            report = builder.validate_source_diagnostics(directory, {'tool_sha256': sha(directory / 'govulncheck'),
                                                       'analysis': {'source': result}}, head)
            cid = report['container']['id']
            child = group.child(cid)
            require(report.get('cgroup') == str(child) and not child.exists(), 'Analyzer cgroup membership or exit unavailable')
            require([e['exit'] for e in report['events'] if e['action'] == 'die'] == [str(result['status'])],
                    'Missing exact analyzer die event')
            observed = [s['cgroup'] for s in report['samples'] if 'cgroup' in s]
            require(observed and all(int(s['memory.max']) == 10 * GIB for s in observed), 'Analyzer hard limit not proven')
            terminal = group.empty_snapshot()
            diagnostic.update(terminal=terminal, container_id=cid, analyzer_cleanup=report['cleanup'],
                              analyzer_exit=result['status'], oom_killed=report['container']['state']['oom'])
            require(terminal['ram_peak_bytes'] >= max(int(s['memory.peak']) for s in observed)
                    and terminal['swap_peak_bytes'] >= max(int(s.get('memory.swap.peak', '0')) for s in observed)
                    and all(terminal['memory_events'][key] >= report['cgroup_events_max'].get(key, 0)
                            for key in REQUIRED_EVENTS), 'Terminal counters conflict with task samples')
            acquisition = json.loads((directory / 'source-go-acquisition.json').read_text())
            seconds = (builder.RYUK.ADVISORY.timestamp(acquisition['completed_at']) -
                       builder.RYUK.ADVISORY.timestamp(acquisition['started_at'])).total_seconds()
            available = min(s['host_memory_kib']['MemAvailable'] for s in report['samples']) * 1024
            free_swap = min(s['host_memory_kib']['SwapFree'] for s in report['samples']) * 1024
            diagnostic.update(seconds=seconds, host_available_bytes_min=available, host_swap_free_bytes_min=free_swap,
                              source_receipt=result)
            if (result['status'] or report['container']['state']['oom'] or terminal['ram_peak_bytes'] > 9 * GIB
                    or any(e['action'] in ('oom', 'kill') for e in report['events'])
                    or terminal['swap_peak_bytes'] != 0 or any(
                        terminal['memory_events'].get(k, 0) for k in (*REQUIRED_EVENTS, 'oom_group_kill'))
                    or available < 3 * GIB or free_swap < 2 * GIB or not 0 < seconds < 900):
                diagnostic.update(status='FAIL', error='Observed source analysis exceeds reviewed resource criteria')
            else:
                diagnostic['advisory'], _, _ = verify_stream(builder.RYUK.ADVISORY, verify, directory, 'source', result)
                diagnostic['status'] = 'PASS'
    except Exception as error:
        diagnostic.update(status='BLOCKED', error=str(error))
        if result is None:
            raise
    finally:
        diagnostic['completed_at'] = now()
        write(directory / 'terminal-accounting.json', diagnostic)
    return result, diagnostic


def trivy_phase(builder, verifier, directory):
    original = builder.source_analysis
    expected = directory / 'first'
    second = directory / 'independent-source'

    def collect_source(analyzer, args, location, head):
        require(location == expected, 'Unexpected source evidence directory')
        result, diagnostic = terminal_analysis(builder, verifier, original, analyzer, args, location, head)
        if diagnostic['status'] == 'PASS':
            second.mkdir()
            (second / 'cache-analysis').mkdir()
            (second / 'cache-analysis/tmp').mkdir()
            shutil.copy2(location / 'govulncheck', second / 'govulncheck')
            mounts = {f'{location.resolve()}:/evidence': f'{second.resolve()}:/evidence',
                      f'{(location / "cache-analysis").resolve()}:/cache': f'{(second / "cache-analysis").resolve()}:/cache'}
            require(all(analyzer.count(entry) == 1 for entry in mounts), 'Unreviewed source analysis mounts')
            try:
                terminal_analysis(builder, verifier, original, [mounts.get(entry, entry) for entry in analyzer], args, second, head)
            finally:
                builder.remove_cache(second / 'cache-analysis')
        else:
            write(directory / 'second-source-status.json', {'status': 'BLOCKED', 'reason': 'First terminal qualification failed'})
        return result

    builder.source_analysis = collect_source
    try:
        builder.build(expected)
        raise ValueError('Source-only tree unexpectedly admitted a production scanner')
    except ValueError as error:
        if str(error) != 'Unexpected scanner builder/compiler':
            raise
    finally:
        builder.source_analysis = original
        for name in ('first', 'repeat'):
            cache = expected / ('cache-' + name)
            if cache.exists():
                builder.remove_cache(cache)
    statuses = {}
    for location in (expected, second):
        path = location / 'terminal-accounting.json'
        statuses[location.name] = json.loads(path.read_text()) if path.is_file() else {'status': 'NOT_TESTED'}
    receipt = json.loads((expected / 'build-receipt.json').read_text())
    for name in ('trivy.first', 'trivy.repeat'):
        if sha(expected / name) != receipt['binary_sha256'] or receipt['binary_sha256'] != receipt['repeat_sha256']:
            raise MeasuredFailure('Trivy independent executable differs')
    verifier.BINARY.elf((expected / 'trivy.first').read_bytes())
    metadata = verifier.buildinfo((expected / 'first-buildinfo.txt').read_text())
    require(metadata == verifier.buildinfo((expected / 'repeat-buildinfo.txt').read_text())
            and metadata['go_version'] == '1.27.4' and metadata['path'] == 'github.com/aquasecurity/trivy/cmd/trivy',
            'Independent Trivy compiler or executable module metadata differs')
    verifier.BINARY.source_match(metadata, (expected / 'source/go.mod').read_text(), (expected / 'source/go.sum').read_text())
    imports = [package_graph(verifier, expected, name + '-source-packages.json', metadata) for name in ('first', 'repeat')]
    modules = [selected_graph(verifier, expected, name + '-source-modules.json', metadata) for name in ('first', 'repeat')]
    require(imports[0] == imports[1] and not any(p.startswith('github.com/docker/docker/') for p in imports[0]),
            'Compiled Trivy package graph drift')
    require(modules[0] == modules[1], 'Independent Trivy module graphs differ')
    functions = json.loads((expected / 'binary-symbols.json').read_text())
    require(functions['binary_sha256'] == receipt['binary_sha256'] and functions['reader_go_version'] == 'go1.27.4'
            and functions['function_count'] == len(functions['functions']) > 10000
            and functions['functions'] == sorted(functions['functions']), 'Incomplete real Trivy function census')
    require(sha(expected / 'image.tar') == receipt['archive_sha256'] and sha(expected / 'layer.tar') == receipt['layer_sha256']
            and 'sha256:' + sha(expected / 'image-config.json') == receipt['image_id'], 'Trivy archive or OCI identity drift')
    reports = {}
    for mode in ('source', 'binary'):
        if receipt['analysis'][mode]['status'] != 0:
            reports[mode] = {'status': 'FAIL', 'exit': receipt['analysis'][mode]['status']}
        else:
            reports[mode] = trivy_advisory(verifier, builder.RYUK.ADVISORY, expected, mode, receipt['analysis'][mode],
                                           metadata, imports[0], functions['functions'])
    if statuses[second.name]['status'] == 'PASS':
        reports['independent-source'] = trivy_advisory(verifier, builder.RYUK.ADVISORY, second, 'source',
                                                      statuses[second.name]['source_receipt'], metadata, imports[0], functions['functions'])
        require(reports['source']['findings'] == reports['independent-source']['findings'], 'Independent source findings differ')
    write(directory / 'raw-readback.json', {'reproducibility': 'PASS', 'binary_sha256': receipt['binary_sha256'],
                                           'advisory': reports, 'historical_admission': 'BLOCKED',
                                           'image_configuration': receipt['image_id'], 'compiled_packages': len(imports[0]),
                                           'compiled_modules': len(metadata['dependencies']),
                                           'selected_modules': len(modules[0]),
                                           'functions': functions['function_count']})
    if any(d['status'] == 'FAIL' for d in statuses.values()) or any(d.get('status') == 'FAIL' for d in reports.values()):
        raise MeasuredFailure('At least one Trivy execution exceeds qualification criteria')
    require(all(d['status'] == 'PASS' for d in statuses.values()), 'Two independent terminal Trivy assessments unavailable')


def verify_ryuk_sdk(builder, directory):
    import tarfile
    path = directory / 'go1.26.11.linux-amd64.tar.gz'
    run(['curl', '--fail', '--silent', '--show-error', '--location', '--proto', '=https', '--max-time', '120',
         '--max-filesize', '66999944', '--output', str(path),
         'https://go.dev/dl/go1.26.11.linux-amd64.tar.gz'], timeout=125)
    require(sha(path) == RYUK_SDK_SHA, 'Ryuk SDK bytes differ from the official release')
    run(['docker', 'pull', '--platform', 'linux/amd64', builder.BUILDER], timeout=150)
    cid = output('docker', 'create', '--platform', 'linux/amd64', builder.BUILDER)
    try:
        require(re.fullmatch('[0-9a-f]{64}', cid), 'Missing Ryuk compiler container ID')
        with tarfile.open(path, mode='r:gz') as archive:
            for relative, digest in RYUK_SDK_TOOLS.items():
                target = directory / ('ryuk-sdk-' + relative.replace('/', '_'))
                run(['docker', 'cp', cid + ':/usr/local/go/' + relative, str(target)], timeout=35)
                require(hashlib.sha256(archive.extractfile('go/' + relative).read()).hexdigest()
                        == sha(target) == digest, 'Actual Ryuk builder tool bytes differ from the official SDK')
    finally:
        output('docker', 'rm', cid)
    write(directory / 'compiler-proof.json', {'compiler': 'go1.26.11', 'builder': builder.BUILDER,
                                             'sdk_sha256': RYUK_SDK_SHA, 'tools': RYUK_SDK_TOOLS})


def ryuk_phase(builder, verifier, directory):
    verify_ryuk_sdk(builder, directory)
    records = []
    for name in ('first', 'repeat'):
        location = directory / name
        volume = 'ryuk-candidate-build'
        marker = uuid.uuid4().hex
        require(not output('docker', 'volume', 'ls', '-q', '--filter', 'name=^' + volume + '$'), 'Ryuk build volume already exists')
        require(output('docker', 'volume', 'create', '--label', 'enterprise-shop.qualification=' + marker, volume) == volume,
                'Ryuk volume ownership unavailable')
        try:
            try:
                builder.build(location)
                raise ValueError('Source-only tree unexpectedly admitted a production Ryuk image')
            except ValueError as error:
                if str(error) != 'Candidate source/build/image identity drift':
                    raise
            receipt = json.loads((location / 'build-receipt.json').read_text())
            require(sha(location / 'ryuk') == sha(location / 'ryuk.repeat') == receipt['binary_sha256'], 'Ryuk executable changed')
            verifier.BINARY.elf((location / 'ryuk').read_bytes())
            metadata = verifier.BINARY.buildinfo((location / 'buildinfo.txt').read_text())
            require(metadata['go_version'] == '1.26.11', 'Unexpected Ryuk compiler')
            verifier.BINARY.source_match(metadata, (location / 'source/go.mod').read_text(), (location / 'source/go.sum').read_text())
            imports = package_graph(verifier, location, 'source-packages.json', metadata)
            graph = selected_graph(verifier, location, 'source-modules.json', metadata)
            reports = {}
            for mode in ('source', 'binary'):
                report, sbom, _ = verify_stream(builder.ADVISORY, verifier, location, mode, receipt['analysis'][mode])
                verify_graph(sbom, metadata, mode)
                if report['findings']:
                    raise MeasuredFailure('Unexpected independent Ryuk advisory findings')
                reports[mode] = report
            records.append((receipt, reports, (imports, graph)))
        finally:
            require(output('docker', 'volume', 'inspect', '--format', '{{index .Labels "enterprise-shop.qualification"}}',
                           volume) == marker, 'Ryuk volume ownership changed')
            output('docker', 'volume', 'rm', volume)
    if ((directory / 'first/ryuk').read_bytes() != (directory / 'repeat/ryuk').read_bytes()
            or (directory / 'first/image.tar').read_bytes() != (directory / 'repeat/image.tar').read_bytes()
            or records[0][2] != records[1][2]):
        raise MeasuredFailure('Ryuk failed independent build reproducibility')
    info = records[0][0]
    actual, configuration = builder.BINARY.inspect_configuration(info['image_reference'], info['image_id'], directory / 'runtime-identity.tar')
    write(directory / 'raw-readback.json', {'reproducibility': 'PASS', 'receipt': info, 'advisory': [r[1] for r in records],
          'native_id': actual['Id'], 'configuration': configuration, 'historical_admission': 'BLOCKED'})
    return info['image_reference']


def ryuk_smoke(directory, builder, reference):
    marker = uuid.uuid4().hex
    label = 'enterprise-shop.qualification=' + marker
    owned = []
    record = {'status': 'BLOCKED', 'cleanup': False}
    try:
        victim = output('docker', 'create', '--network', 'none', '--label', label, builder.BUILDER, 'true')
        owned.append((victim, marker))
        control = output('docker', 'create', '--network', 'none', '--label', label + '-control', builder.BUILDER, 'true')
        owned.append((control, marker + '-control'))
        ryuk = output('docker', 'run', '--detach', '--label', 'enterprise-shop.qualification=' + marker + '-reaper',
                      '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
                      '--publish', '127.0.0.1::8080', '--mount', 'type=bind,src=/var/run/docker.sock,dst=/var/run/docker.sock', reference)
        owned.append((ryuk, marker + '-reaper'))
        record.update(fixture=victim, control=control, reaper=ryuk, docker_image=inspect(ryuk)['Image'])
        port = int(output('docker', 'port', ryuk, '8080/tcp').removeprefix('127.0.0.1:'))
        for attempt in range(20):
            try:
                peer = socket.create_connection(('127.0.0.1', port), timeout=1)
                break
            except OSError:
                if attempt == 19:
                    raise
                time.sleep(0.25)
        with peer, peer.makefile('rb') as wire:
            peer.settimeout(3)
            peer.sendall(('label=' + label + '\n').encode('ascii'))
            require(wire.readline(16) == b'ACK\n', 'Ryuk did not acknowledge the owned fixture filter')
        record['acknowledged'] = True
        status = int(output('docker', 'wait', ryuk, timeout=70))
        remaining = output('docker', 'ps', '-a', '--no-trunc', '--filter', 'label=' + label, '--format', '{{.ID}}').splitlines()
        control_state = inspect(control)['State']['Status']
        record.update(exit=status, remaining=remaining, control_state=control_state)
        if status != 0 or remaining or control_state != 'created':
            record['status'] = 'FAIL'
            raise MeasuredFailure('Ryuk failed scoped resource cleanup')
        record['status'] = 'PASS'
    finally:
        try:
            if 'reaper' in record:
                with (directory / 'runtime-smoke.log').open('w') as log:
                    run(['docker', 'logs', record['reaper']], timeout=10, stdout=log, stderr=subprocess.STDOUT)
        finally:
            try:
                for cid, expected in owned:
                    require(re.fullmatch('[0-9a-f]{64}', cid), 'Smoke fixture cleanup ID missing')
                    ids = output('docker', 'ps', '-a', '--no-trunc', '--filter', 'id=' + cid, '--format', '{{.ID}}').splitlines()
                    if ids:
                        require(ids == [cid] and inspect(cid)['Config']['Labels']['enterprise-shop.qualification'] == expected,
                                'Smoke fixture cleanup ownership changed')
                        output('docker', 'rm', '--force', cid)
                record['cleanup'] = True
            finally:
                write(directory / 'runtime-smoke.json', record)


def buildkit_probe(directory, binary):
    context = directory / 'buildkit-input'
    context.mkdir()
    (context / 'Dockerfile').write_text('FROM scratch\nCOPY identity.txt /identity.txt\n')
    (context / 'identity.txt').write_text(TREE + '\n')
    tag = 'local/enterprise-go-qualification:oci-' + uuid.uuid4().hex
    metadata_path = directory / 'buildkit.json'
    try:
        run(['docker', 'buildx', 'build', '--platform', 'linux/amd64', '--load', '--tag', tag,
             '--metadata-file', str(metadata_path), str(context)], timeout=90)
        proof = directory / 'buildkit-identity.tar'
        observed, configuration = binary.inspect_configuration(tag, proof=proof)
        document = json.loads(metadata_path.read_text())
        publisher = document.get('containerimage.descriptor')
        try:
            require('containerimage.descriptor' not in document or isinstance(publisher, dict), 'Malformed BuildKit descriptor')
            require(publisher is not None or 'containerimage.config.digest' in document, 'Missing BuildKit publisher identity')
            require(binary.configuration_evidence(proof, observed['Id'], publisher) == configuration,
                    'BuildKit descriptor does not bind the original OCI export')
            require(document.get('containerimage.config.digest', configuration) == configuration
                    and document.get('containerimage.digest', observed['Id']) == observed['Id'],
                    'BuildKit configuration or native identity mismatch')
        except ValueError as error:
            raise MeasuredFailure(str(error)) from error
        write(directory / 'buildkit-readback.json', {'native_id': observed['Id'], 'configuration': configuration})
    finally:
        if output('docker', 'image', 'ls', '-q', '--filter', 'reference=' + tag):
            output('docker', 'image', 'rm', tag)


def bind(candidate, harness, directory):
    head = output('git', '-C', str(candidate), 'rev-parse', 'HEAD')
    harness_head = output('git', '-C', str(harness), 'rev-parse', 'HEAD')
    require(os.environ['GITHUB_EVENT_NAME'] == 'push' and os.environ['GITHUB_REF'] == BRANCH
            and os.environ['RUNNER_ENVIRONMENT'] == 'github-hosted' and os.environ['GITHUB_RUN_ATTEMPT'] == '1'
            and os.environ['GITHUB_REPOSITORY'] == REPOSITORY
            and os.environ['GITHUB_SHA'] == harness_head
            and head == CANDIDATE
            and output('git', '-C', str(candidate), 'rev-parse', 'HEAD^{tree}') == TREE
            and output('git', '-C', str(harness), 'rev-parse', 'HEAD^1') == PREVIOUS_HARNESS
            and output('git', '-C', str(harness), 'rev-parse', 'HEAD^2') == head,
            'Evidence run is detached from the reviewed source or harness')
    for checkout in (candidate, harness):
        require(not output('git', '-C', str(checkout), 'status', '--porcelain', '--untracked-files=no'),
                'Tracked source changed after checkout')
    write(directory / 'binding.json', {'kind': 'unqualified evidence', 'candidate_commit': head, 'candidate_tree': TREE,
          'harness_commit': harness_head, 'harness_tree': output('git', '-C', str(harness), 'rev-parse', 'HEAD^{tree}'),
          'harness_parent': PREVIOUS_HARNESS,
          'run_id': os.environ['GITHUB_RUN_ID'], 'run_attempt': os.environ['GITHUB_RUN_ATTEMPT'], 'ref': BRANCH})


def main():
    command, source, harness, evidence = sys.argv[1:]
    candidate, harness, directory = (Path(p).resolve() for p in (source, harness, evidence))
    directory.mkdir(parents=True, exist_ok=True)
    bind(candidate, harness, directory)
    os.chdir(candidate)
    sys.path.insert(0, str(candidate / 'scripts'))
    trivy = load(candidate / 'scripts/build-trivy-candidate.py', 'candidate_trivy_builder')
    checks = {}
    if command == 'preflight':
        checks.update(prerequisite='runner preflight', lifecycle=dict.fromkeys(('late', 'oom'), 'NOT_TESTED'))
        try:
            preflight(directory, trivy, checks)
            checks['runner'] = 'PASS'
        except Exception as error:
            checks.update(runner='BLOCKED', error=str(error))
            for mode in checks['lifecycle']:
                if checks['prerequisite'] == 'terminal cgroup ' + mode + ' fixture':
                    checks['lifecycle'][mode] = 'BLOCKED'
            print('Blocked prerequisite ' + checks['prerequisite'] + ' - ' + runner_swap.diagnostic(error), flush=True)
        write(directory / 'preflight-status.json', checks)
        print('Preflight ' + checks['runner'] + ' - terminal lifecycle ' + json.dumps(checks['lifecycle']), flush=True)
        return 0 if checks['runner'] == 'PASS' else 1
    require(command in ('collect', 'collect-trivy')
            and json.loads((directory / 'preflight-status.json').read_text())['runner'] == 'PASS',
            'Hosted preflight did not pass')
    verifier = load(candidate / 'scripts/trivy_candidate_evidence.py', 'candidate_trivy_verify')
    ryuk_verifier = load(candidate / 'scripts/ryuk_candidate_evidence.py', 'candidate_ryuk_verify')
    phases = {'buildkit': lambda path: buildkit_probe(path, trivy.RYUK.BINARY),
              'trivy': lambda path: trivy_phase(trivy, verifier, path),
              'ryuk': lambda path: ryuk_smoke(path, trivy.RYUK, ryuk_phase(trivy.RYUK, ryuk_verifier, path))}
    active = ('trivy',) if command == 'collect-trivy' else tuple(phases)
    checks = dict.fromkeys(phases, 'NOT_TESTED')
    for name in active:
        path = directory / name
        path.mkdir()
        print('Collecting ' + name + ' evidence', flush=True)
        try:
            phases[name](path)
            checks[name] = 'PASS'
        except Exception as error:
            checks[name] = 'FAIL' if isinstance(error, MeasuredFailure) else 'BLOCKED'
            print(checks[name] + ' ' + name + ' - ' + str(error), flush=True)
            write(path / 'error.json', {'status': checks[name], 'error': str(error)})
        write(directory / 'collection-status.json', {'evidence': checks, 'admission': 'BLOCKED'})
    return 0 if all(checks[name] == 'PASS' for name in active) else 1


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print('Qualification blocked: ' + str(error), file=sys.stderr)
        sys.exit(1)
