"""Fresh acquisition evidence for the pinned authoritative Go analyzer."""
import hashlib
import json
import re
import subprocess
import urllib.request
from datetime import datetime, timezone

DATABASE = 'https://vuln.go.dev'
ENDPOINT = DATABASE + '/index/db.json'
MAX_AGE = 172800


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def timestamp(value):
    require(isinstance(value, str) and re.fullmatch(
        r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?Z', value),
        'Malformed Go acquisition timestamp')
    return datetime.fromisoformat(value.removesuffix('Z') + '+00:00')


def utc_now():
    return datetime.now(timezone.utc).isoformat().replace('+00:00', 'Z')


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, 'Duplicate Go acquisition metadata key')
        result[key] = value
    return result


def read_metadata(data):
    value = json.loads(data, object_pairs_hook=unique_object)
    require(isinstance(value, dict) and set(value) == {'modified'}, 'Malformed Go database metadata')
    return timestamp(value['modified'])


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError('Go database authority redirect is forbidden')


def acquire(directory, mode, phase):
    started = utc_now()
    request = urllib.request.Request(ENDPOINT, headers={'Cache-Control': 'no-cache', 'Pragma': 'no-cache'})
    # Default HTTPS certificate verification and inherited proxy/CA trust apply.
    with urllib.request.build_opener(NoRedirect()).open(request, timeout=60) as response:
        require(response.status == 200 and response.geturl() == ENDPOINT, 'Failed Go database authority retrieval')
        data = response.read(4097)
        require(len(data) <= 4096, 'Oversized Go database metadata')
    completed = utc_now()
    require(read_metadata(data) <= timestamp(started), 'Future Go database metadata')
    (directory / f'{mode}-db-{phase}.json').write_bytes(data)
    return {'url': ENDPOINT, 'status': 200, 'started_at': started, 'completed_at': completed, 'sha256': sha(data)}


def collect(command, args, directory, mode, timeout):
    require(mode in ('source', 'binary'), 'Wrong Go acquisition mode')
    before = acquire(directory, mode, 'before')
    started = utc_now()
    output = directory / f'{mode}-govulncheck.json'
    with output.open('w') as out, (directory / f'{mode}-govulncheck.log').open('w') as err:
        status = subprocess.run(command + ['-db=' + DATABASE] + args,
                                stdout=out, stderr=err, timeout=timeout).returncode
    completed = utc_now()
    after = acquire(directory, mode, 'after')
    result = {'status': status, 'sha256': sha(output.read_bytes())}
    record = dict(result, started_at=started, completed_at=completed, before=before, after=after)
    data = (json.dumps(record, indent=2) + '\n').encode()
    (directory / f'{mode}-go-acquisition.json').write_bytes(data)
    return dict(result, acquisition_sha256=sha(data))


def validate(directory, mode, evidence, config, now=None):
    """Bind recent retrieval, analyzer output and an unchanged database revision."""
    now = now or datetime.now(timezone.utc)
    data = (directory / f'{mode}-go-acquisition.json').read_bytes()
    require(sha(data) == evidence['acquisition_sha256'], 'Go acquisition record integrity drift')
    record = json.loads(data, object_pairs_hook=unique_object)
    require(type(record['status']) is int and type(evidence['status']) is int
            and record['status'] == evidence['status'] == 0
            and record['sha256'] == evidence['sha256']
            == sha((directory / f'{mode}-govulncheck.json').read_bytes()), 'Failed/mismatched Go acquisition analysis')
    require(config['db'] == DATABASE, 'Wrong Go database authority')
    modified = timestamp(config['db_last_modified'])
    bounds, revisions = [], []
    for phase in ('before', 'after'):
        response = record[phase]
        require(response['url'] == ENDPOINT and type(response['status']) is int and response['status'] == 200,
                'Failed Go database authority retrieval')
        body = (directory / f'{mode}-db-{phase}.json').read_bytes()
        require(sha(body) == response['sha256'], 'Go database metadata integrity drift')
        revisions.append(read_metadata(body))
        bounds.append((timestamp(response['started_at']), timestamp(response['completed_at'])))
    started, completed = timestamp(record['started_at']), timestamp(record['completed_at'])
    require(bounds[0][0] <= bounds[0][1] <= started <= completed <= bounds[1][0] <= bounds[1][1] <= now,
            'Future/contradictory Go acquisition timestamps')
    require((now - bounds[0][0]).total_seconds() < MAX_AGE, 'Stale Go advisory acquisition evidence')
    require(modified == revisions[0] == revisions[1] and modified <= bounds[0][0],
            'Future/contradictory Go database revision')
