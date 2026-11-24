"""Provision job-owned swap on a disposable GitHub-hosted Linux VM."""
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import urllib.error
import uuid

from accounting import GIB, output, require, write


SIZE = 4 * GIB
MIN_FREE = 2 * GIB
ROOT = Path('/var/tmp')


def diagnostic(error):
    if isinstance(error, urllib.error.HTTPError):
        return f'Official endpoint returned HTTP {error.code}'
    if isinstance(error, subprocess.CalledProcessError):
        return f'Command exited {error.returncode}'
    if isinstance(error, subprocess.TimeoutExpired):
        return f'Command exceeded its {error.timeout}s deadline'
    if isinstance(error, urllib.error.URLError):
        return 'Official HTTPS endpoint unavailable'
    if isinstance(error, OSError):
        return error.strerror or type(error).__name__
    return re.sub(r'[^ -~]', ' ', str(error))[:350] if isinstance(error, ValueError) else type(error).__name__


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


def prefix():
    run_id = os.environ.get('GITHUB_RUN_ID', '')
    require(os.environ.get('GITHUB_ACTIONS') == 'true' and os.environ.get('RUNNER_ENVIRONMENT') == 'github-hosted'
            and os.environ.get('RUNNER_OS') == 'Linux' and os.environ.get('GITHUB_RUN_ATTEMPT') == '1'
            and os.environ.get('GITHUB_REPOSITORY') == 'Patrick1986Git/enterprise-shop'
            and os.environ.get('GITHUB_REF') == 'refs/heads/evidence/go-nov24-docker29-6285a517'
            and re.fullmatch(r'[0-9]{1,20}', run_id), 'Swap manipulation requires the disposable GitHub-hosted Linux job')
    return 'enterprise-shop-go-nov24-' + run_id + '-a1-'


def owned(path):
    root = ROOT.lstat()
    require(path.parent == ROOT and re.fullmatch(re.escape(prefix()) + r'[0-9a-f]{32}\.swap', path.name)
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


def provision(directory, initial):
    receipt = directory / 'swap-provisioning.json'
    record = {'status': 'BLOCKED', 'before': initial, 'requested_bytes': SIZE}
    try:
        path = ROOT / (prefix() + uuid.uuid4().hex + '.swap')
        require(owned(path) is None, 'Planned swap file already exists')
        record['devices_before'] = devices()
        record['filesystem'] = output('findmnt', '-T', str(ROOT), '-n', '-o', 'FSTYPE')
        record['disk_free_bytes'] = shutil.disk_usage(ROOT).free
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


def cleanup(directory):
    receipt = directory / 'swap-provisioning.json'
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
        result['error'] = str(error)
        raise
    finally:
        write(directory / 'swap-cleanup.json', result)


if __name__ == '__main__':
    directory = Path(sys.argv[1]).resolve()
    directory.mkdir(parents=True, exist_ok=True)
    try:
        prefix()
        result = cleanup(directory)
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print('Temporary swap cleanup BLOCKED - ' + diagnostic(error), flush=True)
        sys.exit(1)
    print('Temporary swap cleanup ' + result['status'] + ' - ' + result['detail'], flush=True)
    if 'after' in result:
        print('Host swap after cleanup (bytes) ' + json.dumps(
            {k: result['after'][k] for k in ('SwapTotal', 'SwapFree')}), flush=True)
