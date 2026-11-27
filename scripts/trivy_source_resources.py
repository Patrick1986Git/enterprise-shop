"""Collect and verify bounded terminal Trivy resources on a disposable CI runner."""
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import uuid


ROOT = Path('/sys/fs/cgroup')
GIB = 1024 ** 3
REQUIRED_EVENTS = ('max', 'oom', 'oom_kill')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def now():
    return datetime.now(timezone.utc).isoformat().replace('+00:00', 'Z')


def run(args, timeout=15, **kwargs):
    return subprocess.run(args, check=True, timeout=timeout, text=True, **kwargs)


def output(*args, timeout=15):
    return run(list(args), timeout=timeout, stdout=subprocess.PIPE).stdout.strip()


def write(path, data):
    path.write_text(json.dumps(data, indent=2) + '\n')


def fields(text):
    result = {}
    for line in text.splitlines():
        key, raw = line.split()
        require(key not in result and raw.isdecimal(), 'Malformed cgroup counter')
        result[key] = int(raw)
    return result


def number(text):
    require(text.isdecimal(), 'Malformed cgroup measurement')
    return int(text)


class Group:
    def __init__(self, path, name):
        self.path, self.name = path, name
        self.fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        stat = os.fstat(self.fd)
        self.identity = (stat.st_dev, stat.st_ino)

    def read(self, name):
        stat = self.path.stat(follow_symlinks=False)
        require((stat.st_dev, stat.st_ino) == self.identity, 'Accounting cgroup identity changed')
        fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=self.fd)
        with os.fdopen(fd, 'r', encoding='ascii') as stream:
            body = stream.read(16385)
        require(len(body) <= 16384, 'Oversized cgroup value')
        return body.strip()

    def membership(self):
        stat = fields(self.read('cgroup.stat'))
        return {'populated': fields(self.read('cgroup.events'))['populated'],
                'descendants': stat['nr_descendants'], 'dying_descendants': stat.get('nr_dying_descendants'),
                'processes': self.read('cgroup.procs').splitlines()}

    def snapshot(self):
        events = fields(self.read('memory.events'))
        require(all(key in events for key in REQUIRED_EVENTS), 'Required memory events unavailable')
        return {'path': str(self.path), 'device_inode': list(self.identity),
                'ram_peak_bytes': number(self.read('memory.peak')),
                'swap_peak_bytes': number(self.read('memory.swap.peak')),
                'memory_events': events, **self.membership()}

    def empty_snapshot(self):
        first = self.snapshot()
        require(first == self.snapshot(), 'Terminal cgroup values changed during readback')
        require(first['populated'] == first['descendants'] == 0 and not first['processes'],
                'Accounting cgroup still contains work')
        return first

    def child(self, cid):
        require(re.fullmatch('[0-9a-f]{64}', cid), 'Unrelated Docker container ID')
        return self.path / ('docker-' + cid + '.scope')


@contextmanager
def owned_slice(directory, diagnostic):
    runner_identity()
    name = 'enterprisegoqual' + uuid.uuid4().hex + '.slice'
    target = Path('/run/systemd/system') / name
    unit = directory / name
    unit.write_text('[Slice]\nMemoryAccounting=yes\nTasksAccounting=yes\n')
    group = None
    installed = False
    active = False
    diagnostic.update(accounting_unit=name, cleanup=False)
    try:
        require(not target.exists(), 'Accounting slice already exists')
        run(['sudo', '-n', 'install', '-m', '0644', str(unit), str(target)])
        installed = True
        run(['sudo', '-n', 'systemctl', 'daemon-reload'])
        run(['sudo', '-n', 'systemctl', 'start', name])
        active = True
        location = output('systemctl', 'show', '--property=ControlGroup', '--value', name)
        require(location == '/' + name, 'Unexpected systemd slice path')
        group = Group(ROOT / name, name)
        baseline = group.empty_snapshot()
        diagnostic['baseline'] = baseline
        require(baseline['ram_peak_bytes'] == baseline['swap_peak_bytes'] == 0
                and not any(baseline['memory_events'].values()), 'Accounting slice already has resource history')
        yield group
    finally:
        can_remove = True
        if active and (ROOT / name).exists():
            try:
                if group is None:
                    raise ValueError('Accounting slice identity was never bound')
                membership = group.membership()
                require(membership['populated'] == membership['descendants'] == 0 and not membership['processes'],
                        'Accounting slice still contains work')
            except (OSError, ValueError, KeyError) as error:
                diagnostic['cleanup_error'] = str(error)
                can_remove = False
        if group is not None:
            os.close(group.fd)
        if active and can_remove:
            run(['sudo', '-n', 'systemctl', 'stop', name])
            require(not (ROOT / name).exists(), 'Accounting slice survived cleanup')
        if installed and can_remove:
            run(['sudo', '-n', 'rm', '--', str(target)])
            run(['sudo', '-n', 'systemctl', 'daemon-reload'])
        unit.unlink()
        diagnostic['cleanup'] = not target.exists() and not (ROOT / name).exists()
        require(diagnostic['cleanup'], 'Owned accounting slice could not be safely removed')


def inspect(cid):
    require(re.fullmatch('[0-9a-f]{64}', cid), 'Unrelated Docker container ID')
    result = json.loads(output('docker', 'inspect', '--type', 'container', cid))
    require(len(result) == 1 and result[0]['Id'] == cid, 'Container identity mismatch')
    return result[0]


def fixture(directory, builder, mode):
    """Measure a late tmpfs charge or a deliberate OOM within a small test cgroup."""
    require(mode in ('late', 'oom'), 'Unknown lifecycle fixture')
    diagnostic = {'status': 'BLOCKED', 'kind': mode, 'started_at': now()}
    marker = uuid.uuid4().hex
    cid = None
    try:
        with owned_slice(directory, diagnostic) as group:
            memory = '128m' if mode == 'late' else '24m'
            count = '32' if mode == 'late' else '60'
            try:
                cid = output('docker', 'create', '--platform', 'linux/amd64', '--cgroup-parent', group.name,
                             '--memory', memory, '--memory-swap', memory, '--cpus', '1', '--pids-limit', '32',
                             '--network', 'none', '--read-only', '--cap-drop', 'ALL', '--security-opt',
                             'no-new-privileges', '--label', 'enterprise-shop.qualification=' + marker,
                             '--tmpfs', '/fixture:rw,nosuid,nodev,size=80m,mode=1777',
                             '--user', f'{os.getuid()}:{os.getgid()}', builder, 'sh', '-ec',
                             'sleep 3; exec dd if=/dev/zero of=/fixture/late bs=1M count="$1"', 'fixture', count)
                output('docker', 'start', cid)
                running = inspect(cid)
                require(running['HostConfig']['CgroupParent'] == group.name and running['State']['Running'],
                        'Fixture did not start under the owned slice')
                pid = running['State']['Pid']
                require(type(pid) is int and pid > 0, 'Fixture PID missing')
                child = group.child(cid)
                require((Path('/proc') / str(pid) / 'cgroup').read_text().strip() ==
                        '0::/' + str(child.relative_to(ROOT)), 'Running fixture cgroup mismatch')
                diagnostic.update(container_id=cid, running_pid=pid, running_cgroup=str(child),
                                  before_late_charge=group.snapshot())
                status = int(output('docker', 'wait', cid, timeout=35))
                state = inspect(cid)['State']
                require(state['Status'] == 'exited' and state['Pid'] == 0 and state['ExitCode'] == status,
                        'Fixture process exit was not proven')
                diagnostic.update(exit_status=status, oom_killed=state['OOMKilled'], finished_at=state['FinishedAt'])
            finally:
                if cid is not None:
                    require(inspect(cid)['Config']['Labels']['enterprise-shop.qualification'] == marker,
                            'Fixture cleanup ownership changed')
                    output('docker', 'rm', '--force', cid)
                    diagnostic['container_cleanup'] = True
            require(not child.exists(), 'Fixture task cgroup was not removed')
            terminal = group.empty_snapshot()
            diagnostic['terminal'] = terminal
            events = terminal['memory_events']
            require(terminal['swap_peak_bytes'] == 0, 'Fixture used swap')
            if mode == 'late':
                require(status == 0 and not state['OOMKilled'] and not any(
                        events[k] for k in (*REQUIRED_EVENTS, 'oom_group_kill') if k in events)
                        and terminal['ram_peak_bytes'] >= 32 * 1024 ** 2
                        and terminal['ram_peak_bytes'] > diagnostic['before_late_charge']['ram_peak_bytes'],
                        'Terminal peak failed to retain the late allocation')
            else:
                require(status == 137 and state['OOMKilled'] and all(events[k] > 0 for k in REQUIRED_EVENTS),
                        'Terminal counters failed to retain the bounded OOM')
        diagnostic['status'] = 'PASS'
    except Exception as error:
        diagnostic['error'] = str(error)
        raise
    finally:
        write(directory / f'accounting-{mode}-fixture.json', diagnostic)


SIZE = 4 * GIB
MIN_FREE = 2 * GIB
SWAP_ROOT = Path('/var/tmp')


def memory():
    values = dict(line.split(':', 1) for line in Path('/proc/meminfo').read_text().splitlines())
    return {key: int(values[key].split()[0]) * 1024 for key in ('MemTotal', 'MemAvailable', 'SwapTotal', 'SwapFree')}


def devices():
    rows = Path('/proc/swaps').read_text().splitlines()
    require(rows and rows[0].split() == ['Filename', 'Type', 'Size', 'Used', 'Priority'], 'Kernel swap inventory unavailable')
    result = {}
    for line in rows[1:]:
        name, kind, size, used, priority = line.rsplit(None, 4)
        require(name not in result, 'Duplicate kernel swap device')
        result[name] = {'type': kind, 'size_bytes': int(size) * 1024, 'used_bytes': int(used) * 1024, 'priority': int(priority)}
    return result


def runner_identity():
    run_id = os.environ.get('GITHUB_RUN_ID', '')
    attempt = os.environ.get('GITHUB_RUN_ATTEMPT', '')
    require(os.environ.get('GITHUB_ACTIONS') == 'true'
            and os.environ.get('RUNNER_ENVIRONMENT') == 'github-hosted'
            and os.environ.get('RUNNER_OS') == 'Linux'
            and os.environ.get('GITHUB_REPOSITORY') == 'Patrick1986Git/enterprise-shop'
            and os.environ.get('GITHUB_JOB') == 'trivy-candidate'
            and re.fullmatch(r'[1-9][0-9]{0,19}', run_id)
            and re.fullmatch(r'[1-9][0-9]{0,5}', attempt),
            'Terminal resource collection requires the disposable Trivy CI job')
    return run_id, attempt


def prefix():
    run_id, attempt = runner_identity()
    return f'enterprise-shop-trivy-{run_id}-a{attempt}-'


def owned(path):
    root = SWAP_ROOT.lstat()
    require(path.parent == SWAP_ROOT and re.fullmatch(re.escape(prefix()) + r'[0-9a-f]{32}\.swap', path.name)
            and root.st_uid == 0 and stat.S_ISDIR(root.st_mode) and root.st_mode & stat.S_ISVTX,
            'Swap path is outside the job-owned namespace')
    try:
        identity = path.lstat()
    except FileNotFoundError:
        return None
    require(stat.S_ISREG(identity.st_mode) and stat.S_IMODE(identity.st_mode) == 0o600
            and identity.st_uid == 0 and identity.st_nlink == 1, 'Swap file has unexpected ownership or permissions')
    return identity


def privileged(operation, *args, seconds=15):
    try:
        result = subprocess.run(['sudo', '-n', 'timeout', '--kill-after=5s', str(seconds), *args],
                                capture_output=True, text=True, timeout=seconds + 10)
    except subprocess.TimeoutExpired as error:
        raise ValueError(operation + ' exceeded its deadline') from error
    if result.returncode:
        detail = ' '.join(result.stderr.split())[:300]
        raise ValueError(f'{operation} failed with exit {result.returncode}' + (f' ({detail})' if detail else ''))


def publish(path, record):
    temporary = path.with_suffix('.tmp')
    write(temporary, record)
    temporary.replace(path)


def provision_swap(directory, initial):
    path = SWAP_ROOT / (prefix() + uuid.uuid4().hex + '.swap')
    receipt = directory / 'swap-provisioning.json'
    record = {'status': 'BLOCKED', 'before': initial, 'requested_bytes': SIZE}
    try:
        require(owned(path) is None, 'Planned swap file already exists')
        record['devices_before'] = devices()
        record['filesystem'] = output('findmnt', '-T', str(SWAP_ROOT), '-n', '-o', 'FSTYPE')
        record['disk_free_bytes'] = shutil.disk_usage(SWAP_ROOT).free
        require(record['filesystem'] in ('ext4', 'xfs'), 'Temporary swap requires an ext4 or XFS runner volume')
        require(record['disk_free_bytes'] >= SIZE, 'Insufficient runner disk space for 4 GiB of swap')
        # Persist the unique path before the first mutation so cleanup covers partial allocation.
        record['path'] = str(path)
        publish(receipt, record)
        privileged('Allocate non-sparse swap file', 'sh', '-c',
                   'umask 077; exec dd if=/dev/zero of="$1" bs=4M count=1024 conv=excl,fsync oflag=nofollow status=none',
                   'allocate-swap', str(path), seconds=75)
        identity = owned(path)
        require(identity is not None and identity.st_size == SIZE and identity.st_blocks * 512 >= SIZE,
                'Swap file allocation is incomplete or sparse')
        record['identity'] = [identity.st_dev, identity.st_ino]
        record['allocated_bytes'] = identity.st_blocks * 512
        publish(receipt, record)
        privileged('Initialize swap file', 'mkswap', '--', str(path))
        privileged('Activate swap file', 'swapon', '--', str(path))
        active = devices()
        current = memory()
        record.update(devices_after=active, after=current)
        swap = active.get(str(path), {})
        require(swap.get('type') == 'file' and SIZE - os.sysconf('SC_PAGE_SIZE') <= swap['size_bytes'] <= SIZE
                and current['SwapTotal'] == initial['SwapTotal'] + swap['size_bytes'],
                'Kernel inventory or SwapTotal does not confirm activation of the owned 4 GiB swap file')
        require(all(name in active and active[name]['size_bytes'] == device['size_bytes']
                    for name, device in record['devices_before'].items()), 'Pre-existing swap device missing or changed')
        require(current['SwapFree'] >= MIN_FREE, 'Runner has less than 2 GiB of free host swap after activation')
        record['status'] = 'PASS'
        return current
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        record['error'] = str(error)
        raise
    finally:
        publish(receipt, record)


def cleanup_swap(directory, *, recovery=False):
    runner_identity()
    receipt = directory / 'swap-provisioning.json'
    report = 'swap-cleanup-recovery.json' if recovery else 'swap-cleanup.json'
    result = {'status': 'NOT_TESTED', 'detail': 'No temporary swap path allocated'}
    try:
        if not receipt.is_file():
            return result
        setup = json.loads(receipt.read_text())
        if 'path' not in setup:
            return result
        path = Path(setup['path'])
        result.update(path=str(path), status='BLOCKED', before=memory())
        identity = owned(path)
        if identity is not None and 'identity' in setup:
            require([identity.st_dev, identity.st_ino] == setup['identity'], 'Swap file identity changed')
        active = devices()
        if str(path) in active:
            require(identity is not None and 'identity' in setup, 'Active swap file ownership could not be proven')
            privileged('Deactivate owned swap file', 'swapoff', '--', str(path), seconds=75)
        require(str(path) not in devices(), 'Qualification swap remained active after cleanup')
        if identity is not None:
            current = owned(path)
            require(current is not None and (current.st_dev, current.st_ino) == (identity.st_dev, identity.st_ino),
                    'Swap file identity changed during cleanup')
            privileged('Remove owned swap file', 'rm', '--', str(path))
        remaining = devices()
        result.update(after=memory(), remaining_devices=remaining)
        require(owned(path) is None and str(path) not in remaining, 'Qualification swap file survived cleanup')
        require(all(name in remaining and remaining[name]['size_bytes'] == device['size_bytes']
                    for name, device in setup['devices_before'].items()), 'Pre-existing swap device missing or changed')
        result.update(status='PASS', detail='Owned swap inactive and absent')
        return result
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        result.update(status='BLOCKED', error=str(error))
        raise
    finally:
        publish(directory / report, result)


def preflight(directory):
    runner_identity()
    engine = json.loads(output('docker', 'version', '--format', '{{json .}}'))
    cgroup = output('docker', 'info', '--format', '{{.CgroupVersion}} {{.CgroupDriver}}')
    mount = output('findmnt', '-T', str(ROOT), '-no', 'FSTYPE,OPTIONS')
    initial = memory()
    evidence = {'status': 'BLOCKED', 'docker': engine, 'cgroup': cgroup, 'mount': mount, 'before': initial,
                'cpus': len(os.sched_getaffinity(0)), 'disk_free_bytes': shutil.disk_usage(directory).free}
    try:
        require(all(engine[key]['Version'].split('.')[0] == '29' and engine[key]['Os'] == 'linux'
                    and engine[key]['Arch'] == 'amd64' for key in ('Client', 'Server')),
                'Trivy terminal evidence requires Linux/amd64 Docker 29')
        require(cgroup == '2 systemd' and mount.startswith('cgroup2 ') and 'memory_localevents' not in mount,
                'Hierarchical cgroup v2 accounting unavailable')
        require(evidence['cpus'] >= 2 and initial['MemAvailable'] >= 3 * GIB, 'Insufficient host CPU or available RAM')
        run(['sudo', '-n', 'true'])
        current = provision_swap(directory, initial) if initial['SwapFree'] < MIN_FREE else initial
        evidence['active'] = current
        require(current['MemAvailable'] >= 3 * GIB and current['SwapFree'] >= MIN_FREE,
                'Insufficient host memory or free swap before Trivy analysis')
        evidence['status'] = 'PASS'
    finally:
        write(directory / 'resource-host.json', evidence)


def read_evidence(path):
    def unique_fields(pairs):
        result = dict(pairs)
        require(len(result) == len(pairs), 'Duplicate terminal accounting field')
        return result

    require(path.stat().st_size <= 128 * 1024, 'Oversized terminal accounting evidence')
    return json.loads(path.read_text(), object_pairs_hook=unique_fields)


def validate_slice(evidence):
    unit = evidence['accounting_unit']
    require(re.fullmatch('enterprisegoqual[0-9a-f]{32}\\.slice', unit) and evidence['cleanup'] is True,
            'Unrelated or incompletely cleaned accounting slice')
    base, terminal = evidence['baseline'], evidence['terminal']
    require(base['path'] == terminal['path'] == str(ROOT / unit)
            and base['device_inode'] == terminal['device_inode']
            and len(terminal['device_inode']) == 2
            and all(type(n) is int and n > 0 for n in terminal['device_inode']),
            'Terminal cgroup identity changed')
    for value in (base, terminal):
        require(value['populated'] == value['descendants'] == 0 and value['processes'] == []
                and all(type(value[k]) is int for k in ('populated', 'descendants'))
                and all(type(value[k]) is int and value[k] >= 0 for k in ('ram_peak_bytes', 'swap_peak_bytes'))
                and all(k in value['memory_events'] for k in REQUIRED_EVENTS)
                and all(type(n) is int and n >= 0 for n in value['memory_events'].values()),
                'Malformed terminal cgroup values')
    require(not base['ram_peak_bytes'] and not base['swap_peak_bytes']
            and not any(base['memory_events'].values()), 'Accounting scope had prior resource use')
    return terminal


def validate_fixture(evidence, mode):
    terminal = validate_slice(evidence)
    cid = evidence['container_id']
    require(evidence['status'] == 'PASS' and evidence['kind'] == mode and evidence['container_cleanup'] is True
            and re.fullmatch('[0-9a-f]{64}', cid)
            and evidence['running_cgroup'] == terminal['path'] + '/docker-' + cid + '.scope'
            and terminal['swap_peak_bytes'] == 0, 'Terminal accounting fixture failed')
    events = terminal['memory_events']
    if mode == 'late':
        require(evidence['exit_status'] == 0 and evidence['oom_killed'] is False
                and terminal['ram_peak_bytes'] >= 32 * 1024 ** 2
                and terminal['ram_peak_bytes'] > evidence['before_late_charge']['ram_peak_bytes']
                and all(events.get(k, 0) == 0 for k in (*REQUIRED_EVENTS, 'oom_group_kill')),
                'Late allocation is missing from terminal cgroup evidence')
    else:
        require(evidence['exit_status'] == 137 and evidence['oom_killed'] is True
                and all(events[k] > 0 for k in REQUIRED_EVENTS), 'Fixture OOM was not retained in terminal counters')


def validate_preflight(directory):
    host = read_evidence(directory / 'resource-host.json')
    require(host['status'] == 'PASS' and host['cgroup'] == '2 systemd'
            and host['mount'].startswith('cgroup2 ') and 'memory_localevents' not in host['mount']
            and all(host['docker'][key]['Version'].split('.')[0] == '29'
                    and host['docker'][key]['Os'] == 'linux' and host['docker'][key]['Arch'] == 'amd64'
                    for key in ('Client', 'Server'))
            and type(host['cpus']) is int and host['cpus'] >= 2
            and host['before']['MemAvailable'] >= 3 * GIB
            and host['active']['MemAvailable'] >= 3 * GIB and host['active']['SwapFree'] >= MIN_FREE,
            'Missing accepted Trivy resource preflight')
    for mode in ('late', 'oom'):
        validate_fixture(read_evidence(directory / f'accounting-{mode}-fixture.json'), mode)
    cleanup = read_evidence(directory / 'swap-cleanup.json')
    if host['before']['SwapFree'] < MIN_FREE:
        provision = read_evidence(directory / 'swap-provisioning.json')
        path = provision['path']
        require(provision['status'] == cleanup['status'] == 'PASS' and provision['before'] == host['before']
                and provision['after'] == host['active'] and provision['requested_bytes'] == SIZE
                and provision['allocated_bytes'] >= SIZE and path == cleanup['path']
                and provision['devices_after'][path]['type'] == 'file'
                and SIZE - 4096 <= provision['devices_after'][path]['size_bytes'] <= SIZE
                and path not in cleanup['remaining_devices']
                and all(name in cleanup['remaining_devices']
                        and cleanup['remaining_devices'][name]['size_bytes'] == device['size_bytes']
                        for name, device in provision['devices_before'].items()),
                'Temporary host swap was not verified and cleaned')
    else:
        require(cleanup['status'] == 'NOT_TESTED' and not (directory / 'swap-provisioning.json').exists(),
                'Unreviewed temporary swap evidence')


def validate_terminal(evidence, report, acquisition, source, stamp):
    terminal = validate_slice(evidence)
    container = report['container']
    cid = container['id']
    require(evidence['status'] == 'PASS' and re.fullmatch('[0-9a-f]{64}', cid)
            and type(evidence['analyzer_exit']) is int and evidence['analyzer_exit'] == source['status'] == 0
            and evidence['source_receipt'] == source and evidence['container_id'] == cid
            and evidence['analyzer_cleanup'] is report['cleanup'] is True
            and evidence['oom_killed'] is container['state']['oom'] is False
            and report['cgroup'] == terminal['path'] + '/docker-' + cid + '.scope'
            and [e['exit'] for e in report['events'] if e['action'] == 'die'] == ['0']
            and not any(e['action'] in ('oom', 'kill') for e in report['events']), 'Unrelated or failed terminal analyzer evidence')
    live = [s['cgroup'] for s in report['samples'] if 'cgroup' in s]
    require(live and all(int(s['memory.max']) == 10 * GIB for s in live)
            and terminal['ram_peak_bytes'] >= max(int(s['memory.peak']) for s in live)
            and terminal['swap_peak_bytes'] >= max(int(s.get('memory.swap.peak', '0')) for s in live)
            and all(terminal['memory_events'][key] >= report['cgroup_events_max'].get(key, 0)
                    for key in REQUIRED_EVENTS), 'Terminal counters conflict with live analyzer samples')
    elapsed = (stamp(acquisition['completed_at']) - stamp(acquisition['started_at'])).total_seconds()
    ram = min(s['host_memory_kib']['MemAvailable'] for s in report['samples']) * 1024
    swap = min(s['host_memory_kib']['SwapFree'] for s in report['samples']) * 1024
    require(stamp(evidence['started_at']) <= stamp(report['started_at'])
            <= stamp(report['completed_at']) <= stamp(evidence['completed_at'])
            and evidence['seconds'] == elapsed and evidence['host_available_bytes_min'] == ram
            and evidence['host_swap_free_bytes_min'] == swap, 'Contradictory terminal timing or host headroom')
    require(0 < elapsed < 900 and ram >= 3 * GIB and swap >= MIN_FREE
            and 0 < terminal['ram_peak_bytes'] <= 9 * GIB and terminal['swap_peak_bytes'] == 0
            and all(terminal['memory_events'].get(k, 0) == 0 for k in (*REQUIRED_EVENTS, 'oom_group_kill')),
            'Terminal Trivy analyzer resource budget exceeded')
    return evidence


if __name__ == '__main__':
    try:
        directory = Path(sys.argv[1]).resolve()
        cleanup_swap(directory, recovery=True)
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        sys.exit('Trivy swap cleanup failed: ' + str(error))
