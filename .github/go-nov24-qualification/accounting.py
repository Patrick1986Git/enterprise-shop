"""Runner-owned cgroup v2 evidence. Never changes the Docker daemon configuration."""
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
import json
import os
import re
import subprocess
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
