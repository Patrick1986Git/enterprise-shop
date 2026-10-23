import hashlib
import json
import os
import signal
import sys
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / 'docker/java-security'
GUARD = DATA / 'with-verified-blocklist.sh'
DIGEST = '2596bb6dda83c66d96c5a252d7b2ae1f24cc1ae3f1890223b4f3591a8d52bf10'


class JavaBlocklistTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.blocklist = self.directory / 'blocked.certs'
        self.blocklist.write_bytes((DATA / 'blocked.certs').read_bytes())
        self.marker = self.directory / 'started'

    def invoke(self, path=None, env=None):
        return subprocess.run(['/bin/sh', str(GUARD), str(path or self.blocklist),
                               '/bin/sh', '-c', 'touch "$1"', 'probe', str(self.marker)],
                              text=True, capture_output=True, env=env)

    def test_reviewed_source_receipt_binds_complete_artifact(self):
        receipt = json.loads((DATA / 'provenance.json').read_text())
        payload = self.blocklist.read_bytes()
        self.assertEqual(DIGEST, hashlib.sha256(payload).hexdigest())
        self.assertEqual(DIGEST, receipt['sha256'])
        self.assertEqual(5608, len(payload))
        lines = payload.decode().splitlines()
        self.assertEqual('Algorithm=SHA-256', lines[0])
        self.assertEqual(86, len(set(lines[1:])))
        self.assertEqual(86, len(lines)-1)
        for fingerprint in lines[1:]:
            self.assertRegex(fingerprint, r'^[0-9A-F]{64}$')

    def test_verified_data_starts_command(self):
        self.assertEqual(0, self.invoke().returncode)
        self.assertTrue(self.marker.exists())

    def test_tampering_truncation_wrong_algorithm_and_missing_data_prevent_start(self):
        original = self.blocklist.read_bytes()
        for contents in (b'', original[:-1], original.replace(b'SHA-256', b'SHA-512'),
                         original + b'00\n'):
            with self.subTest(contents=contents[:20]):
                self.blocklist.write_bytes(contents)
                result = self.invoke()
                self.assertNotEqual(0, result.returncode)
                self.assertIn('refusing to start', result.stderr)
                self.assertFalse(self.marker.exists())
        self.blocklist.unlink()
        self.assertNotEqual(0, self.invoke().returncode)
        self.assertFalse(self.marker.exists())

    def test_directory_instead_of_file_prevents_start(self):
        self.assertNotEqual(0, self.invoke(self.directory).returncode)
        self.assertFalse(self.marker.exists())

    def test_unavailable_checksum_tool_prevents_start(self):
        result = self.invoke(env={**os.environ, 'PATH': str(self.directory)})
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.marker.exists())

    def test_command_exit_status_is_preserved(self):
        result = subprocess.run(['/bin/sh', str(GUARD), str(self.blocklist), '/bin/sh', '-c', 'exit 37'])
        self.assertEqual(37, result.returncode)

    def test_newline_path_cannot_redirect_checksum_verification(self):
        tampered = Path(str(self.blocklist) + '\nignored')
        tampered.write_text('tampered')
        self.assertNotEqual(0, self.invoke(tampered).returncode)
        self.assertFalse(self.marker.exists())

    def test_adversarial_filenames_verify_the_requested_contents(self):
        original = self.blocklist.read_bytes()
        for name in ('line\nbreak', 'back\\slash', '-leading-dash', 'space and\ttab'):
            with self.subTest(name=name):
                path = self.directory / name
                path.write_bytes(original)
                self.assertEqual(0, self.invoke(path).returncode)
                self.assertTrue(self.marker.exists())
                self.marker.unlink()
                path.write_bytes(b'tampered')
                self.assertNotEqual(0, self.invoke(path).returncode)
                self.assertFalse(self.marker.exists())

    def test_symlink_verifies_current_target_and_rejects_replaced_or_missing_target(self):
        link = self.directory / 'linked-blocklist'
        link.symlink_to(self.blocklist)
        self.assertEqual(0, self.invoke(link).returncode)
        self.marker.unlink()
        replacement = self.directory / 'replacement'
        replacement.write_bytes(b'tampered')
        replacement.replace(self.blocklist)
        self.assertNotEqual(0, self.invoke(link).returncode)
        self.assertFalse(self.marker.exists())
        self.blocklist.unlink()
        self.assertNotEqual(0, self.invoke(link).returncode)
        self.assertFalse(self.marker.exists())

    def test_fifo_is_rejected_without_waiting_for_a_writer(self):
        fifo = self.directory / 'fifo'
        os.mkfifo(fifo)
        result = subprocess.run(['/bin/sh', str(GUARD), str(fifo)],
                                capture_output=True, timeout=5)
        self.assertNotEqual(0, result.returncode)

    def test_unreadable_data_prevents_start(self):
        self.blocklist.chmod(0)
        self.addCleanup(self.blocklist.chmod, 0o600)
        self.assertNotEqual(0, self.invoke().returncode)
        self.assertFalse(self.marker.exists())

    def test_arguments_process_identity_and_signal_delivery_are_preserved(self):
        argument = 'space ; $HOME " literal'
        command = [sys.executable, '-u', '-c',
                   'import os,sys,time; print(os.getpid()); print(sys.argv[1]); time.sleep(30)', argument]
        with subprocess.Popen(['/bin/sh', str(GUARD), str(self.blocklist), *command],
                              stdout=subprocess.PIPE, text=True) as process:
            try:
                self.assertEqual(process.pid, int(process.stdout.readline()))
                self.assertEqual(argument, process.stdout.readline().rstrip('\n'))
                process.send_signal(signal.SIGTERM)
                self.assertEqual(-signal.SIGTERM, process.wait(timeout=5))
            finally:
                if process.poll() is None:
                    process.kill()

    def test_retained_fixtures_are_immutable_and_inherited_restrictions_remain(self):
        fixtures = ROOT / 'scripts/tests/fixtures/java-blocklist'
        receipt = json.loads((fixtures / 'provenance.json').read_text())
        for name, entry in receipt['files'].items():
            self.assertEqual(entry['sha256'], hashlib.sha256((fixtures / name).read_bytes()).hexdigest())
        inherited = set((fixtures / 'vendor-blocked.certs').read_text().splitlines()[1:])
        self.assertEqual(38, len(inherited))
        self.assertTrue(inherited <= set(self.blocklist.read_text().splitlines()[1:]))

    def test_both_stages_guard_base_identity_installation_and_java_execution(self):
        dockerfile = (ROOT / 'Dockerfile').read_text()
        self.assertEqual(2, dockerfile.count('96572f243f31c2ef81a6e627542e596f6a9295cff3c7ae095c1b595cb1457ded'))
        self.assertEqual(2, dockerfile.count('COPY --chmod=0444 docker/java-security/blocked.certs'))
        self.assertEqual(2, dockerfile.count('with-verified-blocklist /opt/java/openjdk/lib/security/blocked.certs ./mvnw'))
        self.assertIn('ENTRYPOINT ["/usr/local/bin/with-verified-blocklist", "/opt/java/openjdk/lib/security/blocked.certs", "/opt/java/openjdk/bin/java",', dockerfile)
        self.assertIn('USER app:app', dockerfile)


if __name__ == '__main__':
    unittest.main()
