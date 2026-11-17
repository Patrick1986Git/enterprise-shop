#!/usr/bin/env python3
"""Collect public default-JSSE evidence. Every result remains UNQUALIFIED."""
import argparse
import base64
import hashlib
import importlib.util
import json
import os
import re
import shutil
import subprocess
import tarfile
import tempfile
import time
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('builder_policy', ROOT / 'scripts/validate-builder-security.py')
POLICY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(POLICY)
PROBE = ROOT / 'scripts/jdk-certificate-evidence/CertificateEvidence.java.source'
FIXTURES = ROOT / 'scripts/tests/fixtures/jdk-certificates'
AUTHORITY_SHA256 = '43224a83fbe39073bf39234aed54abaa4fab078e5bae6c0a477addd2f4801965'
MAX_OUTPUT = 24 * 1024 * 1024
STAGES = {'builder': 'builder', 'runtime': 'app'}
OVERRIDES = ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'JAVA_OPTS', 'MAVEN_OPTS',
             'MAVEN_ARGS', 'USE_SYSTEM_CA_CERTS', 'SSL_CERT_FILE', 'SSL_CERT_DIR')
COVERAGE = {'compromised_tls_chains': 'NOT_TESTED', 'java_signed_code': 'NOT_TESTED',
            'alternate_certificate_encodings': 'NOT_TESTED'}
LABEL = 'enterprise-shop.certificate-evidence'
PROVIDERS = ['SUN', 'SunRsaSign', 'SunEC', 'SunJSSE', 'SunJCE', 'SunJGSS', 'SunSASL',
             'XMLDSig', 'SunPCSC', 'JdkLDAP', 'JdkSASL', 'SunPKCS11']
JAVA_PROPERTIES = {'java.home', 'java.version', 'java.runtime.version', 'java.vendor', 'java.vendor.version',
                   'java.specification.version', 'os.name', 'os.arch'}
FILE_FIELDS = {'path', 'canonical_path', 'symlinks', 'mode', 'uid', 'gid', 'size', 'sha256', 'base64'}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def read(path):
    require(path.is_file() and not path.is_symlink() and path.stat().st_size <= MAX_OUTPUT,
            'Missing or oversized evidence')
    return POLICY.read_json(path)


def write(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + '\n')


def run(command, *, timeout=60, limit=MAX_OUTPUT, env=None):
    """Bound disk-backed output and time; never expose command output on failure."""
    with tempfile.TemporaryFile() as output, tempfile.TemporaryFile() as errors:
        process = subprocess.Popen(command, stdout=output, stderr=errors, env=env)
        started = time.monotonic()
        try:
            while process.poll() is None:
                require(time.monotonic() - started < timeout
                        and os.fstat(output.fileno()).st_size <= limit
                        and os.fstat(errors.fileno()).st_size <= limit, 'Evidence command exceeded limits')
                time.sleep(0.02)
            require(process.returncode == 0 and os.fstat(output.fileno()).st_size <= limit
                    and os.fstat(errors.fileno()).st_size <= limit, 'Evidence command failed; output withheld')
            output.seek(0)
            return output.read(limit + 1)
        finally:
            if process.poll() is None:
                process.kill()
            process.wait()


def compile_probe(directory):
    directory.mkdir()
    source = directory / 'CertificateEvidence.java'
    source.write_bytes(PROBE.read_bytes())
    # Sanitizing the compiler does not alter the measured image's environment.
    environment = {k: v for k, v in os.environ.items() if k not in (*OVERRIDES, 'JDK_JAVAC_OPTIONS', 'CLASSPATH')}
    version = run(['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-version'], env=environment, limit=4096)
    require(re.fullmatch(rb'javac 21\.[^\r\n]+\s*', version), 'Java 21 compiler module required')
    classes = directory / 'classes'
    classes.mkdir()
    # The compiler and platform modules are both required to be Java 21. This
    # also supports a Java-21 installation without javac's ct.sym archive.
    run(['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-source', '21', '-target', '21', '-Xlint:all', '-Werror',
         '-d', str(classes), str(source)], env=environment)
    require({p.name for p in classes.iterdir()} == {'CertificateEvidence.class'}, 'Unexpected probe classes')
    receipt = {'source_sha256': sha(PROBE.read_bytes()), 'class_sha256': sha((classes / 'CertificateEvidence.class').read_bytes()),
               'compiler': version.decode().strip(), 'platform_feature': 21}
    write(directory / 'compiler.json', receipt)
    return classes


def invoke_probe(classes, *args):
    require((len(args) == 2 and args[0] in ('--fixture-store', '--replay-store'))
            or (len(args) == 3 and args[0] == '--fixture-chain'), 'Host invocation is fixture/replay only')
    environment = {k: v for k, v in os.environ.items() if k not in OVERRIDES and k != 'CLASSPATH'}
    raw = run(['java', '-Xmx256m', '-cp', str(classes), 'CertificateEvidence', *map(str, args)], env=environment)
    with tempfile.TemporaryDirectory() as temporary:
        path = Path(temporary) / 'probe.json'
        path.write_bytes(raw)
        return read(path)


def authority():
    require(sha((FIXTURES / 'manifest.json').read_bytes()) == AUTHORITY_SHA256, 'Stale certificate authority manifest')
    manifest = read(FIXTURES / 'manifest.json')
    require(manifest['schema_version'] == 1 and manifest['behavioral_coverage'] == COVERAGE,
            'Unsupported certificate authority coverage')
    for name, expected in manifest['files'].items():
        require(Path(name).name == name and sha((FIXTURES / name).read_bytes()) == expected['sha256']
                and (FIXTURES / name).stat().st_size == expected['bytes'], 'Altered upstream certificate fixture')
    return manifest


def image_context(directory, source):
    recorded = directory / 'source-sha.txt'
    require(re.fullmatch(r'[0-9a-f]{40}', source) and recorded.is_file() and not recorded.is_symlink()
            and recorded.stat().st_size <= 64 and recorded.read_text().strip() == source,
            'Wrong source identity')
    images = {}
    for stage, prefix in STAGES.items():
        observed = read(directory / f'{prefix}-image.json')
        require(isinstance(observed, list) and len(observed) == 1, 'Ambiguous image stage')
        observed = observed[0]
        config_id, bases = POLICY.image_provenance(read(directory / f'{prefix}-build.json'), observed,
                                                  directory / f'{prefix}-identity.tar', stage == 'builder')
        with tarfile.open(directory / f'{prefix}-identity.tar') as archive:
            _, raw, _, _ = POLICY.IDENTITY.configuration_metadata(archive, observed['Id'])
        config = POLICY.IDENTITY.image_json(raw)['config']
        images[stage] = {'native_id': observed['Id'], 'configuration_id': config_id, 'platform': 'linux/amd64',
                         'bases': bases, 'config': config}
    require(images['builder']['bases']['21-jdk-jammy'] == images['runtime']['bases']['21-jdk-jammy'],
            'Builder base changed between stages')
    return {'source_sha': source, 'images': images, 'authority_sha256': AUTHORITY_SHA256}


def context(directory, source):
    expected_context = image_context(directory, source)
    manifest = authority()
    provenance = read(directory / 'jdk-advisory-provenance.json')
    vdr_path = directory / 'temurin-vdr.json'
    require(vdr_path.is_file() and not vdr_path.is_symlink() and vdr_path.stat().st_size <= MAX_OUTPUT
            and provenance['repository'] == POLICY.VDR_REPOSITORY and provenance['tag'] == manifest['vdr_tag']
            and provenance['sha256'] == manifest['vdr_sha256'] == sha(vdr_path.read_bytes()), 'Stale advisory authority')
    records = read(vdr_path)['vulnerabilities']
    for identity, expected_digest in manifest['advisory_records'].items():
        actual = [r for r in records if r['id'] == identity]
        require(len(actual) == 1 and sha(json.dumps(actual[0], sort_keys=True, separators=(',', ':')).encode()) == expected_digest,
                'Altered advisory record')
    return expected_context


def launch_options(config, stage):
    environment = {}
    for value in config.get('Env') or []:
        key, separator, item = value.partition('=')
        require(separator and key not in environment, 'Malformed image environment')
        environment[key] = item
    require(all(not environment.get(name) for name in OVERRIDES)
            and all(not value for name, value in environment.items()
                    if any(word in name.upper() for word in ('TRUSTSTORE', 'JAVA_SECURITY', 'PROVIDER',
                                                             'PASSWORD', 'PASSWD', 'SECRET', 'TOKEN', 'PRIVATE_KEY'))),
            'Unreviewed JVM or trust-store override; values withheld')
    require(environment.get('JAVA_HOME') == '/opt/java/openjdk', 'Unreviewed Java location')
    require(config.get('WorkingDir') == ('/workspace' if stage == 'builder' else '/app'), 'Wrong image stage')
    if stage == 'runtime':
        expected = ['java', '-XX:MaxRAMPercentage=75.0', '-Djava.security.egd=file:/dev/./urandom', '-jar', '/app/app.jar']
        require(config.get('Entrypoint') == expected and not config.get('Cmd'), 'Unreviewed runtime invocation')
        return expected[1:3]
    require(stage == 'builder' and config.get('Entrypoint') in (None, [], ['/__cacert_entrypoint.sh'])
            and config.get('Cmd') in (None, [], ['jshell']),
            'Unreviewed builder invocation')
    return []


def decode_file(value):
    require(isinstance(value, dict) and type(value.get('size')) is int and 0 < value['size'] <= 8 * 1024 * 1024
            and isinstance(value.get('base64'), str) and len(value['base64']) <= 12 * 1024 * 1024,
            'Missing trust-store bytes')
    raw = base64.b64decode(value['base64'], validate=True)
    require(len(raw) == value['size'] and sha(raw) == value['sha256'], 'Altered trust-store bytes')
    return raw


def validate_probe(report, classes, *, fixture=False):
    expected_fields = {'schema_version', 'qualification', 'scope', 'providers', 'java', 'security', 'release',
                       'security_file', 'java_executable', 'blocked_certs', 'jssecacerts', 'store', 'accepted_issuers',
                       'trust_manager_algorithm', 'trust_manager_provider', 'ssl_context_provider'}
    require(set(report) == expected_fields and report['schema_version'] == 1
            and report['qualification'] == 'UNQUALIFIED'
            and report['scope'] == ('OFFLINE_FIXTURE' if fixture else 'IMAGE_DEFAULT_JSSE'), 'Wrong probe scope/schema')
    require(report['jssecacerts'] == {'present': False} and report['trust_manager_provider'] == 'SunJSSE'
            and report['ssl_context_provider'] == 'SunJSSE' and report['trust_manager_algorithm'] == 'PKIX',
            'Unrecognized effective trust configuration')
    properties = report['java']
    require(set(properties) == JAVA_PROPERTIES and properties['java.specification.version'] == '21', 'Wrong Java feature/properties')
    require([p['name'] for p in report['providers']] == PROVIDERS
            and report['security']['ssl.TrustManagerFactory.algorithm'] == 'PKIX', 'Unreviewed provider configuration')
    fields = report['release']['fields']
    require(set(report['store']) == FILE_FIELDS | {'type', 'certificates'}
            and set(report['release']) == (FILE_FIELDS - {'base64'}) | {'fields'}
            and set(fields) == {'JAVA_VERSION', 'JAVA_VERSION_DATE', 'IMPLEMENTOR', 'IMPLEMENTOR_VERSION'}
            and set(report['security_file']) == FILE_FIELDS - {'base64'}
            and set(report['java_executable']) == FILE_FIELDS,
            'Incomplete or unrecognized file measurement')
    if not fixture:
        version = fields['JAVA_VERSION']
        observed_version = POLICY.numeric_version(version)
        require(fields['IMPLEMENTOR'] == properties['java.vendor'] == 'Eclipse Adoptium'
                and observed_version[0] == 21 and len(observed_version) > 1 and properties['java.version'] == version
                and properties['java.home'] == '/opt/java/openjdk'
                and properties['os.name'] == 'Linux' and properties['os.arch'] in ('amd64', 'x86_64'), 'Wrong Temurin identity')
        build = re.fullmatch(re.escape(version) + r'\+([1-9][0-9]{0,8})(?:-LTS)?', properties['java.runtime.version'])
        require(build is not None and properties['java.vendor.version'] == fields['IMPLEMENTOR_VERSION']
                == f'Temurin-{version}+{build[1]}', 'Contradictory Temurin build identity')
        require(report['store']['path'] == '/opt/java/openjdk/lib/security/cacerts', 'Wrong selected trust store')
        require(report['store']['uid'] == 0 and report['store']['mode'] & 0o022 == 0,
                'Unreviewed trust-store ownership or permissions')
    raw = decode_file(report['store'])
    with tempfile.TemporaryDirectory() as temporary:
        path = Path(temporary) / 'store'
        path.write_bytes(raw)
        replay = invoke_probe(classes, '--replay-store', path)['store']
    certificates = report['store']['certificates']
    require(replay['certificates'] == certificates and replay['type'] == report['store']['type'], 'Conflicting parsed DER evidence')
    require(report['accepted_issuers'] == sorted({c['sha256'] for c in certificates}), 'Incomplete effective trust inventory')
    for field in ('release', 'security_file', 'java_executable'):
        require(re.fullmatch(r'[0-9a-f]{64}', report[field]['sha256'])
                and type(report[field]['size']) is int and report[field]['size'] > 0, 'Missing Java file identity')
    decode_file(report['java_executable'])
    if report['blocked_certs'] != {'present': False}:
        require(set(report['blocked_certs']) == FILE_FIELDS, 'Unrecognized blocked certificate measurement')
        decode_file(report['blocked_certs'])
    manifest = authority()
    forbidden = [c for c in manifest['certificates'] if c['category'] != 'permitted_root']
    require(not any(c['sha256'] == bad['sha256'] or c['spki_sha256'] == bad['spki_sha256']
                    for c in certificates for bad in forbidden), 'Withdrawn root or compromised intermediate is trusted')
    return {'trusted_certificates': len(certificates), 'behavioral_coverage': COVERAGE.copy(),
            'qualification': 'UNQUALIFIED'}


def inspect_container(cid, nonce, image):
    data = POLICY.IDENTITY.image_json(run(['docker', 'container', 'inspect', cid], limit=1024 * 1024))
    require(isinstance(data, list) and len(data) == 1, 'Ambiguous collector container')
    item = data[0]
    require(item['Id'] == cid and item['Config']['Labels'].get(LABEL) == nonce
            and item['Config']['Image'] == image['native_id']
            and item['Image'] in (image['native_id'], image['configuration_id']), 'Collector container identity mismatch')
    return item


def capture(stage, image, classes, output, nonce):
    options = launch_options(image['config'], stage)
    require(',' not in str(classes.resolve()) and '\n' not in str(classes.resolve()), 'Unsupported probe mount path')
    observed, configuration = POLICY.IDENTITY.inspect_configuration(image['native_id'],
        expected=image['configuration_id'], proof=output / f'{stage}-fresh-identity.tar')
    require(observed['Id'] == image['native_id'] and configuration == image['configuration_id'], 'Stale actual image')
    # The inspected launch context must agree with original configuration bytes.
    for key in ('Env', 'Entrypoint', 'Cmd', 'User', 'WorkingDir'):
        require(observed['Config'].get(key) == image['config'].get(key), 'Original image launch configuration mismatch')
    command = ['docker', 'create', '--platform', 'linux/amd64', '--network', 'none', '--read-only', '--cap-drop', 'ALL',
               '--security-opt', 'no-new-privileges', '--pids-limit', '64', '--memory', '512m', '--cpus', '1',
               '--label', LABEL + '=' + nonce, '--mount', f'type=bind,src={classes.resolve()},dst=/evidence-probe,readonly',
               '--entrypoint', '/opt/java/openjdk/bin/java', image['native_id'], *options,
               '-cp', '/evidence-probe', 'CertificateEvidence']
    cid = run(command, limit=4096).decode().strip()
    require(re.fullmatch(r'[0-9a-f]{64}', cid), 'Missing owned container identity')
    try:
        item = inspect_container(cid, nonce, image)
        host = item['HostConfig']
        require(host['ReadonlyRootfs'] and host['NetworkMode'] == 'none' and not host['Privileged']
                and host['CapDrop'] == ['ALL'] and not host.get('CapAdd')
                and 'no-new-privileges' in host['SecurityOpt'] and host['PidsLimit'] == 64
                and host['Memory'] == 512 * 1024 * 1024 and host['NanoCpus'] == 1000000000
                and len(item['Mounts']) == 1 and item['Mounts'][0]['Destination'] == '/evidence-probe'
                and item['Mounts'][0]['Source'] == str(classes.resolve()) and item['Mounts'][0]['RW'] is False,
                'Unreviewed probe isolation')
        require(item['Config'].get('Env') == image['config'].get('Env')
                and item['Config'].get('User') == image['config'].get('User')
                and item['Config'].get('WorkingDir') == image['config'].get('WorkingDir')
                and item['Config']['Entrypoint'] == ['/opt/java/openjdk/bin/java']
                and item['Config']['Cmd'] == [*options, '-cp', '/evidence-probe', 'CertificateEvidence'],
                'Probe launch context differs from reviewed image')
        execution_context = (item['HostConfig'], item['Config'], item['Mounts'])
        payload = run(['docker', 'start', '--attach', cid])
        path = output / f'{stage}-probe.json'
        path.write_bytes(payload)
        item = inspect_container(cid, nonce, image)
        require(execution_context == (item['HostConfig'], item['Config'], item['Mounts'])
                and item['State']['Status'] == 'exited' and item['State']['ExitCode'] == 0 and not item['State']['OOMKilled'],
                'Incomplete probe execution')
        write(output / f'{stage}-container.json', {'stage': stage, 'container_id': cid, 'nonce': nonce,
            'native_id': image['native_id'], 'configuration_id': configuration,
            'user': item['Config'].get('User'), 'working_dir': item['Config'].get('WorkingDir'),
            'readonly_rootfs': True, 'network': 'none', 'probe_sha256': sha(payload), 'exit_code': 0})
        summary = validate_probe(read(path), classes)
        final = POLICY.IDENTITY.image_json(run(['docker', 'image', 'inspect', image['native_id']], limit=1024 * 1024))[0]
        require(final['Id'] == image['native_id'] and final['Config'] == observed['Config'], 'Image changed during collection')
        return {'native_id': image['native_id'], 'configuration_id': configuration, 'platform': 'linux/amd64',
                'bases': image['bases'], 'container_id': cid, 'nonce': nonce, 'measurement': summary}
    finally:
        # An inspection failure never authorizes deleting an unverified container.
        inspect_container(cid, nonce, image)
        run(['docker', 'rm', '--force', '--volumes', cid], limit=4096)


def census(directory):
    result = {}
    for path in directory.rglob('*'):
        require(not path.is_symlink(), 'Symlink in evidence bundle')
        if path.is_file() and path != directory / 'envelope.json':
            require(path.stat().st_size <= MAX_OUTPUT, 'Oversized evidence artifact')
            result[path.relative_to(directory).as_posix()] = {'sha256': sha(path.read_bytes()), 'bytes': path.stat().st_size}
    return result


def check_envelope(directory, expected):
    envelope = read(directory / 'envelope.json')
    require(set(envelope) == {'schema_version', 'qualification', 'source_sha', 'authority_sha256', 'coverage', 'images', 'artifacts'}
            and envelope['schema_version'] == 1 and envelope['qualification'] == 'UNQUALIFIED'
            and envelope['source_sha'] == expected['source_sha']
            and envelope['authority_sha256'] == expected['authority_sha256'] == AUTHORITY_SHA256
            and envelope['coverage'] == COVERAGE and set(envelope['images']) == set(STAGES), 'Stale or incomplete evidence envelope')
    require(envelope['artifacts'] == census(directory), 'Altered or incomplete artifact census')
    for stage in STAGES:
        identity = envelope['images'][stage]
        require(set(identity) == {'native_id', 'configuration_id', 'platform', 'bases', 'container_id', 'nonce', 'measurement'}
                and all(identity[k] == expected['images'][stage][k] for k in ('native_id', 'configuration_id', 'platform', 'bases')),
                'Copied or wrong-stage evidence')
        require(re.fullmatch(r'[0-9a-f]{64}', identity['container_id']) and re.fullmatch(r'[0-9a-f]{32}', identity['nonce'])
                and set(identity['measurement']) == {'trusted_certificates', 'behavioral_coverage', 'qualification'}
                and identity['measurement']['qualification'] == 'UNQUALIFIED'
                and identity['measurement']['behavioral_coverage'] == COVERAGE, 'Incomplete measurement identity')
    require(envelope['images']['builder']['container_id'] != envelope['images']['runtime']['container_id']
            and envelope['images']['builder']['nonce'] != envelope['images']['runtime']['nonce'],
            'Copied builder evidence cannot establish runtime coverage')
    return envelope


def validate_bundle(directory, expected):
    envelope = check_envelope(directory, expected)
    authority()
    compiler = read(directory / 'probe/compiler.json')
    require(set(compiler) == {'source_sha256', 'class_sha256', 'compiler', 'platform_feature'}
            and compiler['platform_feature'] == 21 and re.fullmatch(r'javac 21\.[^\r\n]+', compiler['compiler'])
            and compiler['source_sha256'] == sha(PROBE.read_bytes()) == sha((directory / 'probe/CertificateEvidence.java').read_bytes())
            and compiler['class_sha256'] == sha((directory / 'probe/classes/CertificateEvidence.class').read_bytes()), 'Changed probe source or class bytes')
    # Never execute classes supplied by an evidence bundle. Rebuild from current
    # reviewed source and require matching compiler output before store replay.
    with tempfile.TemporaryDirectory() as temporary:
        fresh = compile_probe(Path(temporary) / 'probe')
        require(sha((fresh / 'CertificateEvidence.class').read_bytes()) == compiler['class_sha256'],
                'Retained class does not match independently compiled source')
        require(context(directory / 'provenance', expected['source_sha']) == expected,
                'Retained provenance differs from external expected context')
        for stage in STAGES:
            identity = envelope['images'][stage]
            require(all(identity[k] == expected['images'][stage][k] for k in ('native_id', 'configuration_id', 'platform', 'bases')),
                    'Copied or wrong-stage evidence')
            require(re.fullmatch(r'[0-9a-f]{64}', identity['container_id']) and re.fullmatch(r'[0-9a-f]{32}', identity['nonce']),
                    'Missing collection identity')
            require(POLICY.IDENTITY.configuration_evidence(directory / f'{stage}-fresh-identity.tar', identity['native_id'])
                    == identity['configuration_id'], 'Mismatched retained original OCI proof')
            execution = read(directory / f'{stage}-container.json')
            require(all(execution[k] == identity[k] for k in ('container_id', 'nonce', 'native_id', 'configuration_id'))
                    and execution['stage'] == stage and execution['readonly_rootfs'] is True
                    and execution['network'] == 'none' and execution['exit_code'] == 0
                    and execution['probe_sha256'] == sha((directory / f'{stage}-probe.json').read_bytes())
                    and execution['user'] == expected['images'][stage]['config'].get('User')
                    and execution['working_dir'] == expected['images'][stage]['config'].get('WorkingDir'),
                    'Missing or contradictory owned-container execution evidence')
            summary = validate_probe(read(directory / f'{stage}-probe.json'), fresh)
            require(identity['measurement'] == summary, 'Contradictory measurement summary')
    require(envelope['images']['builder']['container_id'] != envelope['images']['runtime']['container_id'],
            'Copied builder evidence cannot establish runtime coverage')
    return envelope


def check_source(source):
    require(re.fullmatch(r'[0-9a-f]{40}', source)
            and run(['git', '-C', str(ROOT), 'rev-parse', 'HEAD']).decode().strip() == source
            and not run(['git', '-C', str(ROOT), 'status', '--porcelain', '--untracked-files=all']).strip(), 'Clean exact source HEAD required')


def collect(directory, output, source):
    check_source(source)
    POLICY.validate_stages((ROOT / 'Dockerfile').read_text())
    expected = context(directory, source)
    require(not output.exists() and not output.resolve().is_relative_to(directory.resolve()),
            'Fresh evidence destination outside original evidence required')
    for stage, image in expected['images'].items():
        launch_options(image['config'], stage)
    output.mkdir(parents=True)
    provenance = output / 'provenance'
    provenance.mkdir()
    names = ['source-sha.txt', 'temurin-vdr.json', 'jdk-advisory-provenance.json']
    names += [f'{prefix}-{suffix}' for prefix in STAGES.values()
              for suffix in ('image.json', 'build.json', 'identity.tar')]
    for name in names:
        require((directory / name).is_file() and not (directory / name).is_symlink(), 'Missing original provenance artifact')
        shutil.copyfile(directory / name, provenance / name)
    classes = compile_probe(output / 'probe')
    images = {stage: capture(stage, image, classes, output, uuid.uuid4().hex) for stage, image in expected['images'].items()}
    envelope = {'schema_version': 1, 'qualification': 'UNQUALIFIED', 'source_sha': source,
                'authority_sha256': AUTHORITY_SHA256, 'coverage': COVERAGE.copy(), 'images': images, 'artifacts': census(output)}
    write(output / 'envelope.json', envelope)
    return validate_bundle(output, expected)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, required=True, help='Existing original builder/provenance/VDR evidence')
    parser.add_argument('--output', type=Path, required=True, help='New evidence directory, or existing bundle for --replay')
    parser.add_argument('--expected-source', required=True)
    parser.add_argument('--replay', action='store_true')
    args = parser.parse_args()
    try:
        os.chdir(ROOT)
        check_source(args.expected_source)
        result = validate_bundle(args.output, context(args.directory, args.expected_source)) if args.replay else collect(
            args.directory, args.output, args.expected_source)
        print(json.dumps({'qualification': result['qualification'], 'coverage': result['coverage']}))
        return 0
    except (OSError, ValueError, KeyError, TypeError, AttributeError, IndexError, tarfile.TarError):
        print('Certificate evidence failed: unsupported, incomplete or conflicting input; values withheld')
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
