"""Offline regressions for the observed extracted-module cache substitution."""
import importlib.util
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('analyzer_cache', ROOT / 'scripts/go_advisory_acquisition.py')
CACHE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CACHE)


class AnalyzerCacheTests(unittest.TestCase):
    def setUp(self):
        self.command = ['docker', 'run', '--rm', '--read-only', '-v', '/source:/source:ro',
                        '-v', '/evidence:/evidence', '-v', 'reviewed-cache:/tmp', '-e', 'GOPATH=/tmp/go',
                        '-e', 'GOCACHE=/tmp/cache', '-e', 'GOTOOLCHAIN=local', 'reviewed-builder', 'sh', '-ec']

    def isolated(self):
        return CACHE.isolated_analyzer(self.command, 'reviewed-cache:/tmp')

    def test_shared_module_and_compiler_cache_are_unreachable(self):
        original = self.command.copy()
        command = self.isolated()
        volumes = [command[i + 1].split(':')[1] for i, arg in enumerate(command) if arg in ('-v', '--volume')]
        self.assertFalse(any(v == '/tmp' or v.startswith('/tmp/') for v in volumes))
        self.assertEqual('/tmp', command[command.index('--tmpfs') + 1].split(':')[0])
        self.assertEqual(original, self.command)
        self.assertIn('/source:/source:ro', command)
        self.assertIn('/evidence:/evidence', command)
        self.assertEqual(original[-3:], command[-3:])

    def test_effective_native_authentication_and_local_toolchain_are_required(self):
        command = self.isolated()
        env = dict(command[i + 1].split('=', 1) for i, arg in enumerate(command) if arg in ('-e', '--env'))
        self.assertEqual('off', env['GOENV'])
        self.assertEqual('sum.golang.org', env['GOSUMDB'])
        self.assertEqual('https://proxy.golang.org', env['GOPROXY'])
        self.assertEqual('local', env['GOTOOLCHAIN'])
        for key in ('GOPRIVATE', 'GONOSUMDB', 'GONOPROXY'):
            self.assertEqual('', env[key])

    def test_missing_duplicate_or_unreviewed_cache_is_blocking(self):
        for mount in ('absent:/tmp', 'reviewed-cache:/tmp/go', '/host:/tmp'):
            with self.subTest(mount=mount), self.assertRaises(ValueError):
                CACHE.isolated_analyzer(self.command, mount)
        self.command[3:3] = ['-v', 'reviewed-cache:/tmp']
        with self.assertRaises(ValueError): self.isolated()

    def test_a_second_cache_overlay_is_blocking(self):
        for options in (['-v', 'poison:/tmp/go'], ['--volume', 'poison:/tmp/cache'],
                        ['--tmpfs', '/tmp/go'], ['--mount', 'type=volume,dst=/tmp/go']):
            with self.subTest(options=options), self.assertRaises(ValueError):
                CACHE.isolated_analyzer(self.command[:3] + options + self.command[3:], 'reviewed-cache:/tmp')

    def test_disabled_checksums_inherited_config_proxy_or_downloaded_toolchain_are_blocking(self):
        for setting in ('GOSUMDB=off', 'GOENV=/tmp/poison/env', 'GOPROXY=direct', 'GOTOOLCHAIN=auto', 'GOSUMDB',
                        'GOPRIVATE=*', 'GONOSUMDB=golang.org/*', 'GONOPROXY=*'):
            with self.subTest(setting=setting), self.assertRaises(ValueError):
                CACHE.isolated_analyzer(self.command[:3] + ['-e', setting] + self.command[3:], 'reviewed-cache:/tmp')

    def test_module_compiler_or_config_cache_cannot_escape_tmpfs(self):
        for key in ('HOME', 'GOPATH', 'GOMODCACHE', 'GOCACHE', 'GOTMPDIR', 'TMPDIR'):
            for value in ('/shared/poison', '/tmp/../shared/poison'):
                with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                    CACHE.isolated_analyzer(self.command[:3] + ['-e', key + '=' + value] + self.command[3:],
                                            'reviewed-cache:/tmp')

    def test_cache_mount_must_be_a_volume_option_in_a_disposable_container(self):
        self.command[self.command.index('reviewed-cache:/tmp') - 1] = '-e'
        with self.assertRaises(ValueError): self.isolated()
        self.command = ['docker', 'run', '-v', 'reviewed-cache:/tmp']
        with self.assertRaises(ValueError): self.isolated()


# This offline Docker boundary rejects access to a pre-poisoned persistent cache.
# It does not claim to compile Go or represent actual hosted analyzer evidence.
DOCKER = '''#!/usr/bin/env python3
import json,os,sys
from pathlib import Path
args=sys.argv[1:]
if args[0]=='create': print('fixture-container')
elif args[0]=='cp': Path(args[-1]).write_bytes(b'installed-gosu-fixture')
elif args[0]=='run':
 if 'version' in args:
  print('/evidence/gosu: go1.24.13')
 else:
  env=dict(args[i+1].split('=',1) for i,a in enumerate(args) if a=='--env')
  volumes=[args[i+1] for i,a in enumerate(args) if a in ('--volume','-v')]
  if any(v.split(':')[1].startswith('/tmp') for v in volumes): sys.exit(41)
  if '--tmpfs' not in args or args[args.index('--tmpfs')+1].split(':')[0]!='/tmp': sys.exit(42)
  if any(env.get(k)!=v for k,v in {'GOENV':'off','GOSUMDB':'sum.golang.org','GOPROXY':'https://proxy.golang.org','GOTOOLCHAIN':'local'}.items()): sys.exit(43)
  if env.get('GOVERSION')!='go1.24.13': sys.exit(44)
  Path(os.environ['CACHE_TRACE']).write_text(json.dumps({'persistent_cache_access':False,'env':env,'args':args}))
  print('offline fresh-cache analyzer fixture')
elif args[0]!='rm': sys.exit(45)
'''


class GosuCacheExecutionTests(unittest.TestCase):
    def execute(self, edit=lambda text: text):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'bin').mkdir()
            docker = root / 'bin/docker'
            docker.write_text(DOCKER)
            docker.chmod(0o755)
            source = root / '.tmp/gosu-source'
            source.mkdir(parents=True)
            wrapper = source / 'govulncheck-with-excludes.sh'
            wrapper.write_text('#!/usr/bin/env bash\nset -euo pipefail\ngovulncheck ./...\n')
            wrapper.chmod(0o755)
            script = root / 'run.sh'
            script.write_text(edit((ROOT / 'scripts/run-gosu-govulncheck.sh').read_text()))
            trace = root / 'trace.json'
            environment = dict(os.environ, PATH=str(root / 'bin') + os.pathsep + os.environ['PATH'],
                               GOSU_GOVULNCHECK_IMAGE='reviewed-builder-fixture', CACHE_TRACE=str(trace))
            result = subprocess.run(['bash', str(script)], cwd=root, env=environment, text=True, capture_output=True)
            return result, json.loads(trace.read_text()) if trace.exists() else None

    def test_real_wrapper_entrypoint_cannot_reuse_poisoned_module_or_compiler_cache(self):
        result, trace = self.execute()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse(trace['persistent_cache_access'])
        self.assertEqual('go1.24.13', trace['env']['GOVERSION'])

    def test_reintroducing_named_cache_fails_closed_before_findings(self):
        result, trace = self.execute(lambda text: text.replace(
            '--tmpfs /tmp:rw,exec,nosuid,nodev,size=1g,mode=1777', '--volume poisoned-cache:/tmp'))
        self.assertNotEqual(0, result.returncode)
        self.assertIsNone(trace)

    def test_disabling_module_authentication_or_toolchain_guard_fails_closed(self):
        for before, after in (('GOSUMDB=sum.golang.org', 'GOSUMDB=off'), ('GOTOOLCHAIN=local', 'GOTOOLCHAIN=auto'),
                              ('GOENV=off', 'GOENV=/tmp/poison/env')):
            with self.subTest(setting=before):
                result, trace = self.execute(lambda text: text.replace(before, after))
                self.assertNotEqual(0, result.returncode)
                self.assertIsNone(trace)


if __name__ == '__main__':
    unittest.main()

