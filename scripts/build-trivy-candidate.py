#!/usr/bin/env python3
"""Build and independently verify the reviewed minimal Trivy source remediation."""
import argparse
import gzip
import hashlib
import importlib.util
import io
import json
import os
import re
import select
import shutil
import subprocess
import tarfile
import tempfile
import threading
import time
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE = '591e9799316a602e703f0b484f6c6d7b234ec8f3'
ARCHIVE_SHA = '4a5bb1c16ccc55368843eb95ee9a25519a0bfef26609a8b4295f2060af43077e'
BUILDER = 'docker.io/library/golang:1.27.3-alpine3.24@sha256:f2c872bcee949b7be9c490efd02b48a8a2fbf947cdf533f1c58525ec9d8170fd'
BUILDER_ID = 'sha256:2101eb697fc5b5b7f64252fe6bf7a21c4a06a51852c71c3424d44e3f824cbb52'
BUILDER_MANIFEST = 'sha256:cd410a5c6686417875bc4815688e96ae9769f8562debc545d29fe58e73d174f0'
BUILDER_BINARY = '5f5eb6d0b2fb6aa0bb5d5f8c5f1c944e0de955c925f0111af92a9d8f79680b5b'
PATCH_SHA = '9fd37166f858e711be6df21fed4d716a7a98951c9a18cde3789cb743770f13c3'
GO_MOD_SHA = '54eb7f4afb4f86650b63d72deb206c73f24f5fbf1f20e93e93e380eba1ab11c5'
GO_SUM_SHA = 'f0792bca7d673d64f10e76910532f3a1252301ac8e7a0c68aebd31fd5cd4c3b1'
TOOL = 'v1.8.0'
TOOL_SUM = 'h1:clG4qBU6zH5VKjti8n5j8BBuYzoSha392xXMkXS351U='
SDK_SHA = '65efbc80fdc1fa2cd2631cad5b481777a68e8725f34e89f50fc6ac714e379b40'
SDK_TOOLS = {
    'bin/go': BUILDER_BINARY,
    'bin/gofmt': 'fd464d1c9098a6714d06bd3fcd2a1fedcdd291e710241160ebb663bf99adf2f8',
    'pkg/tool/linux_amd64/compile': 'c331d8fc6c4ae9dbe20e8198435db0455f2a5ca55d2d02fc6d7337c906be8283',
    'pkg/tool/linux_amd64/link': 'c9990b939ed48a77ca9a29b33d408d44b56fc4950a28e3d3533025a8dc08b2fe'}
FLAGS = '-p=2 -mod=readonly -buildvcs=false -trimpath -ldflags="-s -w -X github.com/aquasecurity/trivy/pkg/version/app.ver=0.75.0"'
SOURCE_GOMEMLIMIT = '7GiB'
SPEC = importlib.util.spec_from_file_location('ryuk_builder', ROOT / 'scripts/build-ryuk-candidate.py')
RYUK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RYUK)
sha, require, write = RYUK.sha, RYUK.require, RYUK.write


def runtime_directory():
    directory = Path(os.environ.get('RUNNER_TEMP', tempfile.gettempdir())) / 'enterprise-shop-trivy'
    require(not directory.resolve().is_relative_to(ROOT.resolve()), 'Scanner source evidence must stay outside the checkout')
    return directory


def source_files(source):
    return {p.relative_to(source).as_posix(): sha(('symlink:' + os.readlink(p)).encode() if p.is_symlink() else p.read_bytes())
            for p in sorted(source.rglob('*')) if p.is_file() or p.is_symlink()}


def remove_cache(cache):
    def writable(function, path, error):
        # Go intentionally makes downloaded module directories read-only.
        # This callback applies only to this build's disposable private cache.
        Path(path).parent.chmod(0o755)
        function(path)
    shutil.rmtree(cache, onerror=writable)


def prepare(data, source):
    require(sha(data) == ARCHIVE_SHA, 'Unreviewed upstream source archive')
    source.mkdir()
    prefix = 'trivy-' + SOURCE + '/'
    with tarfile.open(fileobj=io.BytesIO(data), mode='r:gz') as archive:
        names = set()
        for member in archive:
            require(member.name == prefix.rstrip('/') or member.name.startswith(prefix), 'Wrong source archive root')
            relative = member.name.removeprefix(prefix)
            require(not Path(relative).is_absolute() and '..' not in Path(relative).parts
                    and member.name not in names, 'Unsafe or duplicate source path')
            names.add(member.name)
            target = source / relative
            if member.isdir():
                if member.name != prefix.rstrip('/'):
                    target.mkdir(parents=True, exist_ok=True)
            elif member.issym():
                require(not Path(member.linkname).is_absolute() and '..' not in Path(member.linkname).parts,
                        'Unsafe source fixture symlink')
                target.parent.mkdir(parents=True, exist_ok=True)
                target.symlink_to(member.linkname)
            else:
                require(member.isfile(), 'Unexpected source device/link')
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(archive.extractfile(member).read())
                target.chmod(member.mode & 0o777)
    before = source_files(source)
    patch = ROOT / '.github/security/trivy/x-net.patch'
    require(sha(patch.read_bytes()) == PATCH_SHA, 'Unreviewed dependency patch')
    subprocess.run(['git', 'apply', '--check', str(patch)], cwd=source, check=True)
    subprocess.run(['git', 'apply', str(patch)], cwd=source, check=True)
    after = source_files(source)
    require(set(before) == set(after) and {n for n in before if before[n] != after[n]} == {'go.mod', 'go.sum'},
            'Patch changed upstream behavior')
    require(sha((source / 'go.mod').read_bytes()) == GO_MOD_SHA
            and sha((source / 'go.sum').read_bytes()) == GO_SUM_SHA, 'Patched Go module identities differ')
    return after


def image_archive(binary, ca, source):
    files = {'usr/local/bin/trivy': (binary, 0o755), 'etc/ssl/certs/ca-certificates.crt': (ca, 0o644)}
    files.update({'contrib/' + p.name: (p.read_bytes(), 0o644) for p in sorted((source / 'contrib').glob('*.tpl'))})
    layer_output = io.BytesIO()
    with tarfile.open(fileobj=layer_output, mode='w', format=tarfile.USTAR_FORMAT) as saved:
        temporary = tarfile.TarInfo('tmp')
        temporary.type, temporary.mode = tarfile.DIRTYPE, 0o1777
        temporary.uid = temporary.gid = temporary.mtime = 0
        saved.addfile(temporary)
        for name, (data, mode) in sorted(files.items()):
            member = tarfile.TarInfo(name)
            member.size, member.mode = len(data), mode
            member.uid = member.gid = member.mtime = 0
            saved.addfile(member, io.BytesIO(data))
    layer = layer_output.getvalue()
    config = RYUK.canonical({'architecture': 'amd64', 'os': 'linux', 'created': '1970-01-01T00:00:00Z',
        'config': {'Entrypoint': ['/usr/local/bin/trivy'], 'WorkingDir': '/',
                   'Env': ['PATH=/usr/local/bin', 'HOME=/root'],
                   'Labels': {'org.opencontainers.image.source': 'https://github.com/Patrick1986Git/enterprise-shop',
                              'org.opencontainers.image.revision': SOURCE}},
        'rootfs': {'type': 'layers', 'diff_ids': ['sha256:' + sha(layer)]}})
    image_id = 'sha256:' + sha(config)
    reference = 'local/enterprise-shop-trivy:sha256-' + sha(config)
    manifest = RYUK.canonical([{'Config': sha(config) + '.json', 'RepoTags': [reference], 'Layers': ['layer.tar']}])
    archive = RYUK.tar_bytes({sha(config) + '.json': (config, 0o644), 'manifest.json': (manifest, 0o644),
                              'layer.tar': (layer, 0o644)})
    census = {n: {'sha256': sha(d), 'size': len(d), 'mode': m} for n, (d, m) in files.items()}
    census['tmp'] = {'type': 'directory', 'mode': 0o1777}
    return reference, image_id, config, layer, archive, census


def run(command, script, directory, name, timeout=1800):
    with (directory / (name + '.log')).open('w') as output:
        result = subprocess.run(command + [BUILDER, 'sh', '-ec', script], stdout=output,
                                stderr=subprocess.STDOUT, timeout=timeout)
    require(result.returncode == 0, name + ' failed; retained log: ' + str(directory / (name + '.log')))


INSPECT_ANALYZER = ('{"id":{{json .Id}},"name":{{json .Name}},'
    '"head":{{json (index .Config.Labels "enterprise-shop.trivy-analysis-head")}},'
    '"image":{{json .Config.Image}},"memory":{{.HostConfig.Memory}},'
    '"swap":{{.HostConfig.MemorySwap}},"cpus":{{.HostConfig.NanoCpus}},'
    '"pids":{{.HostConfig.PidsLimit}},"readonly":{{.HostConfig.ReadonlyRootfs}},'
    '"runtime_env":[{{range .Config.Env}}{{$p := split . "="}}'
    '{{if or (eq (index $p 0) "GOMEMLIMIT") (eq (index $p 0) "GOGC") '
    '(eq (index $p 0) "GOMAXPROCS") (eq (index $p 0) "GODEBUG") (eq (index $p 0) "GOFLAGS")}}'
    '{{json .}},{{end}}{{end}}null],'
    '"state":{"status":{{json .State.Status}},"exit":{{.State.ExitCode}},'
    '"oom":{{.State.OOMKilled}},"pid":{{.State.Pid}},'
    '"started":{{json .State.StartedAt}},"finished":{{json .State.FinishedAt}}}}')
EVENT_ANALYZER = ('{"id":{{json .Actor.ID}},"action":{{json .Action}},"time":{{.TimeNano}},'
    '"signal":{{json (index .Actor.Attributes "signal")}},'
    '"exit":{{json (index .Actor.Attributes "exitCode")}}}')


def diagnostic_command(args):
    result = subprocess.run(['docker'] + args, capture_output=True, timeout=5, check=True)
    require(len(result.stdout) <= 65536, 'Oversized analyzer diagnostic response')
    return result.stdout


def cgroup_directory(pid):
    data = Path(f'/proc/{pid}/cgroup').read_text()[:16384]
    unified = [line[3:] for line in data.splitlines() if line.startswith('0::')]
    require(len(unified) == 1, 'Analyzer cgroup v2 path unavailable')
    root = Path('/sys/fs/cgroup').resolve()
    path = (root / unified[0].lstrip('/')).resolve()
    require(path.is_relative_to(root), 'Analyzer cgroup outside visible namespace')
    return path


def read_counters(path):
    with path.open() as stream:
        raw = stream.read(4097)
    require(len(raw) <= 4096, 'Oversized analyzer cgroup counters')
    return {key: int(value) for key, value in (line.split() for line in raw.splitlines())}


def source_memory_command(analyzer):
    require(analyzer[:3] == ['docker', 'run', '--rm']
            and analyzer[-2:] == [BUILDER, '/evidence/govulncheck'], 'Unexpected source analyzer invocation')
    options = analyzer[:-2]
    for flag, value in [('--memory', '10g'), ('--cpus', '2'), ('--pids-limit', '512')]:
        require(options.count(flag) == 1 and options[options.index(flag) + 1] == value,
                'Source analyzer resource contract drift')
    require(not any(option.startswith(('--memory=', '--cpus=', '--pids-limit=', '-m'))
                    for option in options), 'Source analyzer resource contract override')
    protected = {'GOMEMLIMIT', 'GOGC', 'GOMAXPROCS', 'GODEBUG', 'GOFLAGS'}
    for index, option in enumerate(options):
        require(option != '--env-file' and not option.startswith(('--env-file=', '--env='))
                and not (option.startswith('-e') and option != '-e'), 'Unreviewed analyzer environment option')
        if option in ('-e', '--env'):
            require(options[index + 1].partition('=')[0] not in protected,
                    'Source analyzer runtime override')
    return options + ['-e', 'GOMEMLIMIT=' + SOURCE_GOMEMLIMIT] + analyzer[-2:]


def validate_runtime_environment(values, expected):
    require(type(values) is list and len(values) == len(expected) + 1 and values[-1] is None,
            'Analyzer runtime environment contract drift')
    observed = {}
    for entry in values[:-1]:
        require(type(entry) is str and entry.count('=') == 1, 'Malformed analyzer runtime setting')
        name, value = entry.split('=', 1)
        require(name in expected and name not in observed and value == expected[name],
                'Unexpected or duplicate analyzer runtime setting')
        observed[name] = value
    require(observed == expected, 'Missing analyzer runtime setting')


def source_analysis(analyzer, args, directory, head):
    """Observe only this owned source container; analysis authority stays in collect."""
    require(analyzer[:3] == ['docker', 'run', '--rm'] and re.fullmatch('[0-9a-f]{40}', head),
            'Unexpected source analyzer invocation')
    analyzer = source_memory_command(analyzer)
    output = directory / 'source-termination.json'
    output.unlink(missing_ok=True)
    (directory / 'source-go-acquisition.json').unlink(missing_ok=True)
    name = 'enterprise-shop-trivy-analysis-' + uuid.uuid4().hex
    report = {'schema_version': 1, 'repository_head': head, 'name': name, 'image': BUILDER,
              'analyzer_sha256': sha((directory / 'govulncheck').read_bytes()),
              'started_at': RYUK.ADVISORY.utc_now(), 'args': ['-db=' + RYUK.ADVISORY.DATABASE] + args,
              'samples': [], 'errors': [], 'cgroup_events_max': {}, 'cleanup': False}
    stop = threading.Event()
    identity, group = None, None

    def inspect():
        nonlocal identity
        target = identity['id'] if identity else name
        value = json.loads(diagnostic_command(['inspect', '--format', INSPECT_ANALYZER, target]))
        require(re.fullmatch('[0-9a-f]{64}', value['id']) and value['name'] == '/' + name
                and value['head'] == head and value['image'] == BUILDER, 'Unrelated analyzer container')
        require(cidfile.is_file() and cidfile.read_text().strip() == value['id'], 'Analyzer container ID drift')
        identity = value
        return value

    def sample():
        nonlocal group
        value = inspect()
        record = {'at': RYUK.ADVISORY.utc_now(), 'state': value['state']}
        usage = shutil.disk_usage(directory / 'cache-analysis')
        disk = os.statvfs(directory / 'cache-analysis')
        record['cache'] = {'free_bytes': usage.free, 'free_inodes': disk.f_favail}
        report.setdefault('host_resources', {'logical_cpus': os.cpu_count(),
            'affinity_cpus': len(os.sched_getaffinity(0)), 'disk_total_bytes': usage.total,
            'inode_total': disk.f_files})
        memory = dict(line.split(':', 1) for line in Path('/proc/meminfo').read_text().splitlines())
        record['host_memory_kib'] = {k: int(memory[k].split()[0]) for k in ('MemTotal', 'MemAvailable', 'SwapTotal', 'SwapFree')}
        if group is None and value['state']['pid'] > 0:
            try:
                group = cgroup_directory(value['state']['pid'])
                report['cgroup'] = str(group)
            except (OSError, ValueError):
                report['cgroup_unavailable'] = True
        if group is not None and group.exists():
            try:
                record['memory_events'] = read_counters(group / 'memory.events')
                record['pids_events'] = read_counters(group / 'pids.events')
                memory_stat = read_counters(group / 'memory.stat')
                record['memory_stat'] = {k: memory_stat[k] for k in
                    ('anon', 'file', 'kernel', 'kernel_stack', 'pagetables', 'sock', 'slab')}
                record['cgroup'] = {f: (group / f).read_text()[:128].strip() for f in
                                   ('memory.current', 'memory.peak', 'memory.max', 'pids.current', 'pids.max')}
                record['cgroup'].update({f: (group / f).read_text()[:128].strip() for f in
                    ('memory.swap.current', 'memory.swap.peak', 'memory.swap.max') if (group / f).exists()})
            except OSError:
                record['cgroup_unavailable'] = True  # The kernel removes stopped task cgroups.
        report['samples'].append(record)

    def monitor():
        watched, poller, ticks = None, None, 0
        try:
            while not stop.is_set() and ticks < 400:
                try:
                    sample()
                    if group is not None and watched is None and (group / 'memory.events').exists():
                        try:
                            watched = (group / 'memory.events').open()
                            poller = select.poll()
                            poller.register(watched, select.POLLPRI | select.POLLERR)
                        except OSError:
                            watched, poller = None, None
                except (OSError, ValueError, subprocess.SubprocessError) as error:
                    if identity is not None and len(report['errors']) < 8:
                        report['errors'].append(type(error).__name__)
                ticks += 1
                end = time.monotonic() + 5
                while not stop.is_set() and time.monotonic() < end:
                    if poller is None:
                        stop.wait(0.5)
                    else:
                        poller.poll(500)
                        try:
                            watched.seek(0)
                            raw = watched.read(4097)
                            require(len(raw) <= 4096, 'Oversized memory event counters')
                            counters = {k: int(v) for k, v in (line.split() for line in raw.splitlines())}
                            for key, value in counters.items():
                                report['cgroup_events_max'][key] = max(value, report['cgroup_events_max'].get(key, 0))
                            stop.wait(0.05)
                        except (OSError, ValueError):
                            watched.close()
                            watched, poller = None, None
                            stop.wait(0.5)
        except Exception as error:
            report['errors'].append(type(error).__name__)
        finally:
            if watched is not None:
                watched.close()

    with tempfile.TemporaryDirectory(prefix='enterprise-shop-trivy-monitor-') as temporary:
        cidfile = Path(temporary) / 'container-id'
        command = analyzer[:2] + ['--name', name, '--label', 'enterprise-shop.trivy-analysis-head=' + head,
                                  '--cidfile', str(cidfile)] + analyzer[3:]
        report['command_sha256'] = sha(json.dumps(command + ['-db=' + RYUK.ADVISORY.DATABASE] + args,
                                                 separators=(',', ':')).encode())
        observer = threading.Thread(target=monitor, daemon=True)
        observer.start()
        result = None
        try:
            result = RYUK.ADVISORY.collect(command, args, directory, 'source', 1800)
        finally:
            stop.set()
            observer.join(7)
            if observer.is_alive():
                report['errors'].append('MonitorDeadline')
            try:
                report['container'] = inspect()
                if len(report['samples']) < 400:
                    sample()
                report['events'] = [json.loads(line) for line in diagnostic_command([
                    'events', '--since', report['started_at'], '--until', RYUK.ADVISORY.utc_now(),
                    '--filter', 'container=' + identity['id'], '--filter', 'event=oom',
                    '--filter', 'event=kill', '--filter', 'event=die', '--filter', 'event=stop',
                    '--format', EVENT_ANALYZER]).splitlines()]
            except (OSError, ValueError, subprocess.SubprocessError) as error:
                report['errors'].append(type(error).__name__)
            finally:
                if identity is not None:
                    try:
                        diagnostic_command(['rm', '--force', identity['id']])
                        report['cleanup'] = True
                    except (OSError, ValueError, subprocess.SubprocessError) as error:
                        report['errors'].append(type(error).__name__)
            report['completed_at'] = RYUK.ADVISORY.utc_now()
            report['analysis'] = result
            report['stdout_sha256'] = sha((directory / 'source-govulncheck.json').read_bytes()) if result else None
            report['stderr_sha256'] = sha((directory / 'source-govulncheck.log').read_bytes()) if result else None
            data = (json.dumps(report, indent=2) + '\n').encode()
            require(len(data) <= 1024 * 1024, 'Oversized analyzer termination evidence')
            output.write_bytes(data)
    return dict(result, diagnostics_sha256=sha(output.read_bytes()))


def validate_source_diagnostics(directory, receipt, head, now=None):
    path = directory / 'source-termination.json'
    require(path.stat().st_size <= 1024 * 1024, 'Oversized analyzer termination evidence')
    raw = path.read_bytes()
    evidence = receipt['analysis']['source']
    require(sha(raw) == evidence['diagnostics_sha256'], 'Analyzer diagnostic integrity drift')
    report = json.loads(raw, object_pairs_hook=RYUK.ADVISORY.unique_object)
    actual = report['container']
    require(type(report['schema_version']) is int and report['schema_version'] == 1 and report['repository_head'] == head
            and re.fullmatch('enterprise-shop-trivy-analysis-[0-9a-f]{32}', report['name'])
            and actual['head'] == head and actual['name'] == '/' + report['name']
            and re.fullmatch('[0-9a-f]{64}', actual['id']) and actual['image'] == report['image'] == BUILDER,
            'Missing/unrelated source analyzer diagnostics')
    require(report['analyzer_sha256'] == receipt['tool_sha256']
            and report['args'] == ['-db=https://vuln.go.dev', '-mode=source', '-json', './cmd/trivy']
            and actual['memory'] == 10 * 1024 ** 3 and actual['cpus'] == 2 * 10 ** 9
            and actual['pids'] == 512 and actual['readonly'] is True,
            'Analyzer diagnostic contract drift')
    validate_runtime_environment(actual['runtime_env'], {'GOMEMLIMIT': SOURCE_GOMEMLIMIT})
    require(report['cleanup'] is True and not report['errors'] and 0 < len(report['samples']) <= 400
            and actual['state']['status'] == 'exited' and type(actual['state']['oom']) is bool
            and type(actual['state']['exit']) is int and actual['state']['exit'] == evidence['status']
            and (evidence['status'] != 0 or actual['state']['oom'] is False)
            and report['analysis'] == {k: v for k, v in evidence.items() if k != 'diagnostics_sha256'}
            and report['stdout_sha256'] == evidence['sha256'] == sha((directory / 'source-govulncheck.json').read_bytes())
            and report['stderr_sha256'] == sha((directory / 'source-govulncheck.log').read_bytes()),
            'Incomplete/mismatched analyzer termination evidence')
    acquisition = json.loads((directory / 'source-go-acquisition.json').read_text())
    stamp = RYUK.ADVISORY.timestamp
    now = now or RYUK.ADVISORY.datetime.now(RYUK.ADVISORY.timezone.utc)
    require(stamp(report['started_at']) <= stamp(acquisition['started_at']) <= stamp(acquisition['completed_at'])
            <= stamp(report['completed_at']) <= now and (now - stamp(report['started_at'])).total_seconds()
            < RYUK.ADVISORY.MAX_AGE and
            (stamp(report['completed_at']) - stamp(report['started_at'])).total_seconds() <= 1960,
            'Stale/contradictory analyzer diagnostics')
    require(isinstance(report['events'], list) and len(report['events']) <= 256
            and all(event['id'] == actual['id'] and event['action'] in ('oom', 'kill', 'die', 'stop')
                    for event in report['events']), 'Unrelated analyzer event')
    for sample in report['samples']:
        require(stamp(report['started_at']) <= stamp(sample['at']) <= stamp(report['completed_at'])
                and sample['state']['status'] in ('created', 'running', 'exited')
                and all(type(sample['cache'][k]) is int and sample['cache'][k] >= 0
                        for k in ('free_bytes', 'free_inodes'))
                and all(type(sample['host_memory_kib'][k]) is int and sample['host_memory_kib'][k] >= 0
                        for k in ('MemTotal', 'MemAvailable', 'SwapTotal', 'SwapFree')),
                'Malformed analyzer resource sample')
    return report


def verify_compiler(directory):
    url = 'https://go.dev/dl/go1.27.3.linux-amd64.tar.gz'
    with urllib.request.urlopen(url, timeout=120) as response:
        data = response.read()
    require(sha(data) == SDK_SHA, 'Official Go archive changed')
    (directory / 'sdk.tar.gz').write_bytes(data)
    cid = subprocess.check_output(['docker', 'create', BUILDER], text=True).strip()
    try:
        with tarfile.open(fileobj=io.BytesIO(data), mode='r:gz') as archive:
            for name, digest in SDK_TOOLS.items():
                require(sha(archive.extractfile('go/' + name).read()) == digest, 'Wrong official Go tool bytes')
                target = directory / ('sdk-' + name.replace('/', '_'))
                subprocess.run(['docker', 'cp', cid + ':/usr/local/go/' + name, str(target)], check=True)
                require(sha(target.read_bytes()) == digest, 'Builder compiler differs from official Go archive')
                target.unlink()
    finally:
        subprocess.run(['docker', 'rm', cid], check=True)
    write(directory / 'compiler-sdk-receipt.json', {'archive_url': url, 'archive_sha256': SDK_SHA,
        'builder': BUILDER, 'byte_matches': SDK_TOOLS})


def build(directory):
    require(not directory.exists(), 'Candidate directory already exists')
    directory.mkdir(parents=True)
    with urllib.request.urlopen(f'https://codeload.github.com/aquasecurity/trivy/tar.gz/{SOURCE}', timeout=120) as response:
        data = response.read()
    (directory / 'upstream.tar.gz').write_bytes(data)
    source = directory / 'source'
    hashes = prepare(data, source)
    subprocess.run(['docker', 'pull', '--platform', 'linux/amd64', BUILDER], check=True)
    observed, _ = RYUK.BINARY.inspect_configuration(BUILDER, BUILDER_ID)
    write(directory / 'builder-docker-image.json', observed)
    require(observed['Architecture'] == 'amd64' and observed['Os'] == 'linux'
            and any(r.endswith('@' + BUILDER.split('@')[1]) for r in observed['RepoDigests']), 'Wrong immutable builder')
    for name, digest in [('index', BUILDER.split('@')[1]), ('manifest', BUILDER_MANIFEST)]:
        raw = subprocess.check_output(['docker', 'buildx', 'imagetools', 'inspect', '--raw', BUILDER.split('@')[0] + '@' + digest])
        require('sha256:' + sha(raw) == digest, 'Builder registry identity differs')
        (directory / ('builder-' + name + '.json')).write_bytes(raw)
    require(json.loads((directory / 'builder-manifest.json').read_text())['config']['digest'] == BUILDER_ID,
            'Builder manifest/configuration mismatch')
    platforms = [d for d in json.loads((directory / 'builder-index.json').read_text())['manifests']
                 if d.get('platform', {}).get('os') == 'linux' and d['platform'].get('architecture') == 'amd64']
    require(len(platforms) == 1 and platforms[0]['digest'] == BUILDER_MANIFEST,
            'Builder index/platform manifest mismatch')
    verify_compiler(directory)
    command = ['docker', 'run', '--rm', '--platform', 'linux/amd64', '--user', f'{os.getuid()}:{os.getgid()}',
               '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
               '--pids-limit', '512', '--memory', '10g', '--cpus', '2',
               '-v', f'{source.resolve()}:/source:ro', '-v', f'{directory.resolve()}:/evidence', '-w', '/source',
               '-e', 'GOTOOLCHAIN=local', '-e', 'GOSUMDB=sum.golang.org', '-e', 'GOPROXY=https://proxy.golang.org',
               '-e', 'CGO_ENABLED=0', '-e', 'GOOS=linux', '-e', 'GOARCH=amd64', '-e', 'GOAMD64=v1',
               '-e', 'GOPATH=/cache/go', '-e', 'GOCACHE=/cache/build', '-e', 'TMPDIR=/cache/tmp']
    ca = os.environ.get('AUXILIARY_SCAN_CA_BUNDLE')
    if ca:
        command += ['-v', f'{Path(ca).resolve()}:/run/ca.pem:ro', '-e', 'SSL_CERT_FILE=/run/ca.pem']
    # Distinct empty module AND compiler caches; no output is reused by the second build.
    for name in ('first', 'repeat'):
        cache = directory / ('cache-' + name)
        cache.mkdir()
        build_command = command + ['-v', f'{cache.resolve()}:/cache']
        run(build_command, 'mkdir -p /cache/tmp; '
            f'test "$(sha256sum /usr/local/go/bin/go | cut -d " " -f1)" = {BUILDER_BINARY}; '
            f'go version > /evidence/{name}-compiler.txt; '
            f'go mod download -json > /evidence/{name}-module-downloads.json; go mod verify; '
            f'go build {FLAGS} -o /evidence/trivy.{name} ./cmd/trivy; '
            f'go version -m /evidence/trivy.{name} > /evidence/{name}-buildinfo.txt; '
            f'go list -mod=readonly -deps -json ./cmd/trivy > /evidence/{name}-source-packages.json; '
            f'go list -mod=readonly -m -json all > /evidence/{name}-source-modules.json; '
            f'cp /etc/ssl/certs/ca-certificates.crt /evidence/{name}-ca-certificates.crt', directory, name)
    binary = (directory / 'trivy.first').read_bytes()
    require(binary == (directory / 'trivy.repeat').read_bytes(), 'Nonreproducible scanner executable')
    require(hashes == source_files(source), 'Build modified upstream source')
    inspector = ROOT / '.github/security/trivy/inspect-symbols.go'
    run(command[:3] + ['--network', 'none'] + command[3:] + [
        '-v', f'{(directory / "cache-first").resolve()}:/cache', '-v', f'{inspector.resolve()}:/verifier/inspect-symbols.go:ro'],
        'go run -mod=readonly /verifier/inspect-symbols.go /evidence/trivy.first > /evidence/binary-symbols.json',
        directory, 'binary-symbol-readback')
    first = image_archive(binary, (directory / 'first-ca-certificates.crt').read_bytes(), source)
    repeated = image_archive((directory / 'trivy.repeat').read_bytes(), (directory / 'repeat-ca-certificates.crt').read_bytes(), source)
    require(first == repeated, 'Nonreproducible scratch image')
    reference, image_id, config, layer, archive, census = first
    for name, data in [('image.tar', archive), ('image-config.json', config), ('layer.tar', layer)]:
        (directory / name).write_bytes(data)
    receipt = {'upstream_source_sha': SOURCE, 'upstream_archive_sha256': ARCHIVE_SHA,
        'patch_sha256': PATCH_SHA, 'source_files': hashes, 'go_mod_sha256': GO_MOD_SHA, 'go_sum_sha256': GO_SUM_SHA,
        'builder': BUILDER, 'builder_image_id': BUILDER_ID, 'builder_manifest': BUILDER_MANIFEST,
        'builder_binary_sha256': BUILDER_BINARY, 'go_version': '1.27.3', 'flags': FLAGS,
        'sdk_sha256': SDK_SHA, 'sdk_tools': SDK_TOOLS,
        'symbol_reader_sha256': sha(inspector.read_bytes()),
        'cgo_enabled': '0', 'upx': False, 'independent_cold_caches': True,
        'binary_sha256': sha(binary), 'repeat_sha256': sha((directory / 'trivy.repeat').read_bytes()),
        'image_reference': reference, 'image_id': image_id, 'archive_sha256': sha(archive),
        'layer_sha256': sha(layer), 'filesystem': census,
        'repository_head': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
        'govulncheck_version': TOOL, 'govulncheck_sum': TOOL_SUM, 'analysis': {}}
    write(directory / 'build-receipt.json', receipt)
    cache = directory / 'cache-analysis'
    cache.mkdir()
    analyzer = command + ['-v', f'{cache.resolve()}:/cache']
    run(analyzer, f'mkdir -p /cache/tmp; go install golang.org/x/vuln/cmd/govulncheck@{TOOL}; '
        'cp /cache/go/bin/govulncheck /evidence/govulncheck; '
        'go version -m /evidence/govulncheck > /evidence/govulncheck-buildinfo.txt', directory, 'analyzer-install')
    receipt['tool_sha256'] = sha((directory / 'govulncheck').read_bytes())
    try:
        for mode, args in [('source', ['-mode=source', '-json', './cmd/trivy']),
                           ('binary', ['-mode=binary', '-json', '/evidence/trivy.first'])]:
            invocation = analyzer + [BUILDER, '/evidence/govulncheck']
            receipt['analysis'][mode] = (source_analysis(invocation, args, directory, receipt['repository_head'])
                if mode == 'source' else RYUK.ADVISORY.collect(invocation, args, directory, mode, 1800))
    finally:
        remove_cache(cache)
    receipt['evidence_hashes'] = {p.name: sha(p.read_bytes()) for p in directory.iterdir()
        if p.is_file() and p.name not in {'build-receipt.json', 'image.tar', 'layer.tar', 'upstream.tar.gz',
                                        'trivy.first', 'trivy.repeat', 'govulncheck'}}
    write(directory / 'build-receipt.json', receipt)
    spec = importlib.util.spec_from_file_location('trivy_evidence', ROOT / 'scripts/trivy_candidate_evidence.py')
    evidence = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(evidence)
    reviewed = json.loads(evidence.CONTRACT.read_text())
    scope = json.loads((ROOT / '.github/security/auxiliary-container-scope.json').read_text())
    evidence.inventory(scope['images']['scanner'], reviewed)
    evidence.validate_build(reviewed, directory, receipt['repository_head'])
    subprocess.run(['docker', 'load', '-i', str(directory / 'image.tar')], check=True)
    RYUK.BINARY.inspect_configuration(reference, image_id)
    for name in ('first', 'repeat'):
        remove_cache(directory / ('cache-' + name))
    # Small separately named artifacts permit independent byte readback without
    # duplicating source files or relying on an artifact service's large-file limit.
    payload = directory / 'payload.tar.gz'
    with payload.open('wb') as output, gzip.GzipFile(fileobj=output, mode='wb', mtime=0) as compressed:
        with tarfile.open(fileobj=compressed, mode='w|') as saved:
            for name in ('image.tar', 'trivy.repeat', 'govulncheck', 'upstream.tar.gz', 'sdk.tar.gz'):
                saved.add(directory / name, arcname=name)
    transport = directory / 'transport'
    transport.mkdir()
    with payload.open('rb') as stream:
        index = 0
        while part := stream.read(24 * 1024 * 1024):
            require(index < 12, 'Unexpected candidate payload size')
            (transport / f'{index:02d}').write_bytes(part)
            index += 1
    write(directory / 'transport.json', {'payload_sha256': sha(payload.read_bytes()),
        'chunks': {p.name: sha(p.read_bytes()) for p in sorted(transport.iterdir())}})
    payload.unlink()
    print(json.dumps({k: receipt[k] for k in ('repository_head', 'binary_sha256', 'repeat_sha256',
                                           'image_reference', 'image_id', 'analysis')}, indent=2))
    require(all(a['status'] == 0 for a in receipt['analysis'].values()), 'Independent advisory service or analyzer failed')
    return receipt


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, default=runtime_directory())
    args = parser.parse_args()
    try:
        build(args.directory.resolve())
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        parser.exit(1, 'Trivy preparation failed: ' + str(error) + '\n')
