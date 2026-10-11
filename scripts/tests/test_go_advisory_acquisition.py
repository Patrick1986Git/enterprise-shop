"""Offline acquisition, authority, integrity and replay regressions."""
import copy
import importlib.util
import json
import subprocess
import tempfile
import unittest
import urllib.error
from datetime import datetime, timedelta, timezone
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

SPEC = importlib.util.spec_from_file_location('go_acquisition', Path(__file__).parents[1] / 'go_advisory_acquisition.py')
ACQUISITION = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ACQUISITION)
NOW = datetime(2026, 10, 11, 6, tzinfo=timezone.utc)
MODIFIED = '2026-10-08T22:31:09Z'


def fixture(directory, mode, modified=MODIFIED, now=NOW):
    """Synthetic retrieval envelope; never represents hosted acquisition proof."""
    stamp = lambda seconds: (now + timedelta(seconds=seconds)).isoformat().replace('+00:00', 'Z')
    result = {'status': 0, 'sha256': ACQUISITION.sha((directory / f'{mode}-govulncheck.json').read_bytes())}
    record = dict(result, started_at=stamp(-40), completed_at=stamp(-20))
    for phase, offsets in [('before', (-60, -50)), ('after', (-10, -5))]:
        body = json.dumps({'modified': modified}).encode()
        (directory / f'{mode}-db-{phase}.json').write_bytes(body)
        record[phase] = {'url': ACQUISITION.ENDPOINT, 'status': 200, 'sha256': ACQUISITION.sha(body),
                         'started_at': stamp(offsets[0]), 'completed_at': stamp(offsets[1])}
    data = json.dumps(record).encode()
    (directory / f'{mode}-go-acquisition.json').write_bytes(data)
    return dict(result, acquisition_sha256=ACQUISITION.sha(data))


class GoAcquisitionTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.config = {'db': ACQUISITION.DATABASE, 'db_last_modified': MODIFIED}
        (self.directory / 'source-govulncheck.json').write_text(json.dumps({'config': self.config}))
        self.evidence = fixture(self.directory, 'source')

    def validate(self):
        ACQUISITION.validate(self.directory, 'source', self.evidence, self.config, now=NOW)

    def record(self):
        return json.loads((self.directory / 'source-go-acquisition.json').read_bytes())

    def save(self, record):
        data = json.dumps(record).encode()
        (self.directory / 'source-go-acquisition.json').write_bytes(data)
        self.evidence['acquisition_sha256'] = ACQUISITION.sha(data)

    def test_unchanged_authoritative_database_over_48_hours_is_valid(self):
        self.assertGreater((NOW - ACQUISITION.timestamp(MODIFIED)).total_seconds(), 172800)
        self.validate()

    def test_missing_acquisition_or_response_or_analyzer_is_blocking(self):
        for name in ['source-go-acquisition.json', 'source-db-before.json', 'source-db-after.json', 'source-govulncheck.json']:
            with self.subTest(name=name):
                path = self.directory / name
                data = path.read_bytes()
                path.unlink()
                with self.assertRaises(OSError): self.validate()
                path.write_bytes(data)

    def test_record_response_and_analyzer_byte_tampering_is_blocking(self):
        for name in ['source-go-acquisition.json', 'source-db-before.json', 'source-db-after.json', 'source-govulncheck.json']:
            with self.subTest(name=name):
                path = self.directory / name
                data = path.read_bytes()
                path.write_bytes(data + b' ')
                with self.assertRaises(ValueError): self.validate()
                path.write_bytes(data)

    def test_changed_acquisition_timestamp_without_hash_rebinding_is_blocking(self):
        path = self.directory / 'source-go-acquisition.json'
        record = self.record()
        record['before']['started_at'] = '2026-10-11T05:59:01Z'
        path.write_text(json.dumps(record))
        with self.assertRaisesRegex(ValueError, 'integrity'): self.validate()

    def test_replay_of_old_acquisition_at_48_hour_boundary_is_blocking(self):
        self.evidence = fixture(self.directory, 'source', now=NOW - timedelta(seconds=172740))
        with self.assertRaisesRegex(ValueError, 'Stale'): self.validate()

    def test_future_or_contradictory_acquisition_times_are_blocking(self):
        original = self.record()
        for field, value in [('started_at', '2026-10-11T06:00:01Z'),
                             ('completed_at', '2026-10-11T05:58:00Z')]:
            record = copy.deepcopy(original)
            record[field] = value
            self.save(record)
            with self.assertRaisesRegex(ValueError, 'timestamps'): self.validate()
        record = copy.deepcopy(original)
        record['after']['completed_at'] = '2026-10-11T06:00:01Z'
        self.save(record)
        with self.assertRaisesRegex(ValueError, 'timestamps'): self.validate()

    def test_wrong_authority_or_unsuccessful_http_status_is_blocking(self):
        original = self.record()
        for phase in ('before', 'after'):
            for key, value in [('url', 'http://vuln.go.dev/index/db.json'),
                               ('url', 'https://attacker.invalid/index/db.json'),
                               ('url', ACQUISITION.ENDPOINT + '?cached=1'), ('status', 304), ('status', True)]:
                with self.subTest(phase=phase, key=key, value=value):
                    record = copy.deepcopy(original)
                    record[phase][key] = value
                    self.save(record)
                    with self.assertRaisesRegex(ValueError, 'authority'): self.validate()

    def test_failed_analyzer_or_mismatched_output_receipt_is_blocking(self):
        for status in (1, 2, 3, True):
            record = self.record()
            record['status'] = self.evidence['status'] = status
            self.save(record)
            with self.assertRaisesRegex(ValueError, 'analysis'): self.validate()

    def test_metadata_missing_malformed_duplicate_or_future_is_blocking(self):
        for body in [b'{}', b'null', b'{', b'{"modified":null}',
                     b'{"modified":"2026-10-08"}', b'{"modified":"2026-10-08T22:31:09+00:00"}',
                     b'{"modified":"2026-10-08T22:31:09Z","modified":"2026-10-08T22:31:09Z"}',
                     b'{"modified":"2099-01-01T00:00:00Z"}']:
            with self.subTest(body=body):
                record = self.record()
                (self.directory / 'source-db-before.json').write_bytes(body)
                record['before']['sha256'] = ACQUISITION.sha(body)
                self.save(record)
                with self.assertRaises(ValueError): self.validate()

    def test_database_change_during_scan_or_analyzer_revision_tampering_is_blocking(self):
        body = b'{"modified":"2026-10-09T00:00:00Z"}'
        record = self.record()
        (self.directory / 'source-db-after.json').write_bytes(body)
        record['after']['sha256'] = ACQUISITION.sha(body)
        self.save(record)
        with self.assertRaisesRegex(ValueError, 'revision'): self.validate()
        self.evidence = fixture(self.directory, 'source')
        self.config['db_last_modified'] = '2026-10-09T00:00:00Z'
        with self.assertRaisesRegex(ValueError, 'revision'): self.validate()

    def test_collect_brackets_analyzer_and_explicitly_selects_https_database(self):
        response = Mock(status=200)
        response.geturl.return_value = ACQUISITION.ENDPOINT
        response.read.return_value = json.dumps({'modified': MODIFIED}).encode()
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        opener = Mock()
        opener.open.return_value = response
        events = []
        opener.open.side_effect = lambda *a, **k: events.append('request') or response

        def run(command, **kwargs):
            events.append('analyzer')
            self.assertEqual(['govulncheck', '-db=https://vuln.go.dev', '-mode=source', '-json', '.'], command)
            kwargs['stdout'].write(json.dumps({'config': self.config}))
            return SimpleNamespace(returncode=0)

        with patch.object(ACQUISITION.urllib.request, 'build_opener', return_value=opener), \
                patch.object(ACQUISITION.subprocess, 'run', side_effect=run), \
                patch.object(ACQUISITION, 'utc_now', return_value='2026-10-11T05:59:00Z'):
            self.evidence = ACQUISITION.collect(['govulncheck'], ['-mode=source', '-json', '.'], self.directory, 'source', 600)
        self.assertEqual(['request', 'analyzer', 'request'], events)
        request = opener.open.call_args.args[0]
        self.assertEqual('no-cache', request.get_header('Cache-control'))
        self.validate()

    def test_retrieval_failure_has_no_cached_fallback_or_success_record(self):
        (self.directory / 'source-go-acquisition.json').unlink()
        opener = Mock()
        opener.open.side_effect = urllib.error.URLError('unavailable')
        with patch.object(ACQUISITION.urllib.request, 'build_opener', return_value=opener), \
                patch.object(ACQUISITION.subprocess, 'run') as run:
            with self.assertRaises(urllib.error.URLError):
                ACQUISITION.collect(['govulncheck'], ['.'], self.directory, 'source', 600)
            run.assert_not_called()
        self.assertFalse((self.directory / 'source-go-acquisition.json').exists())

    def test_timeout_cannot_produce_acquisition_success(self):
        (self.directory / 'source-go-acquisition.json').unlink()
        with patch.object(ACQUISITION, 'acquire', return_value={}), \
                patch.object(ACQUISITION.subprocess, 'run', side_effect=subprocess.TimeoutExpired('govulncheck', 600)):
            with self.assertRaises(subprocess.TimeoutExpired):
                ACQUISITION.collect(['govulncheck'], ['.'], self.directory, 'source', 600)
        self.assertFalse((self.directory / 'source-go-acquisition.json').exists())

    def test_failed_post_analysis_retrieval_cannot_produce_success(self):
        (self.directory / 'source-go-acquisition.json').unlink()
        with patch.object(ACQUISITION, 'acquire', side_effect=[{}, urllib.error.URLError('after unavailable')]), \
                patch.object(ACQUISITION.subprocess, 'run', return_value=SimpleNamespace(returncode=0)):
            with self.assertRaises(urllib.error.URLError):
                ACQUISITION.collect(['govulncheck'], ['.'], self.directory, 'source', 600)
        self.assertFalse((self.directory / 'source-go-acquisition.json').exists())

    def test_acquisition_rejects_wrong_response_authority_status_or_metadata(self):
        response = Mock(status=200)
        response.geturl.return_value = ACQUISITION.ENDPOINT
        response.read.return_value = json.dumps({'modified': MODIFIED}).encode()
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        opener = Mock()
        opener.open.return_value = response
        with patch.object(ACQUISITION.urllib.request, 'build_opener', return_value=opener):
            response.status = 304
            with self.assertRaises(ValueError): ACQUISITION.acquire(self.directory, 'source', 'before')
            response.status = 200
            response.geturl.return_value = 'https://attacker.invalid'
            with self.assertRaises(ValueError): ACQUISITION.acquire(self.directory, 'source', 'before')
            response.geturl.return_value = ACQUISITION.ENDPOINT
            response.read.return_value = b'{}'
            with self.assertRaises(ValueError): ACQUISITION.acquire(self.directory, 'source', 'before')
            response.read.return_value = b'x' * 4097
            with self.assertRaises(ValueError): ACQUISITION.acquire(self.directory, 'source', 'before')

    def test_redirect_is_rejected_before_following_another_authority(self):
        with self.assertRaisesRegex(ValueError, 'redirect'):
            ACQUISITION.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://attacker.invalid')


if __name__ == '__main__':
    unittest.main()
