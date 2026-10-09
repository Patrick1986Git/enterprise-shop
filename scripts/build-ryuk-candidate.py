#!/usr/bin/env python3
"""Build an unpublished, deterministic Ryuk candidate from reviewed upstream bytes."""
import argparse
import hashlib
import importlib.util
import io
import json
import os
import shutil
import subprocess
import tarfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TEST_SPEC = importlib.util.spec_from_file_location('ryuk_client_tests', ROOT / 'scripts/ryuk_client_tests.py')
ryuk_client_tests = importlib.util.module_from_spec(TEST_SPEC)
TEST_SPEC.loader.exec_module(ryuk_client_tests)
SOURCE = 'b3726afd6cc2c36628abcc08e9cabac43f587384'
ARCHIVE_SHA = '7754e8598010a543c015c5d14b10372e15a5a00c68e5e5b81300c2cde4d2f01d'
BUILDER = 'docker.io/library/golang:1.26.8-alpine3.24@sha256:8ac98ca534ac3f51e1f420a1dd2c15e74c75cfa0f23f3ad27eb5d7236c349a0c'
TOOL = 'v1.8.0'
TOOL_SUM = 'h1:clG4qBU6zH5VKjti8n5j8BBuYzoSha392xXMkXS351U='


def sha(data):
    return hashlib.sha256(data).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def write(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':')).encode()


def tar_bytes(files):
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode='w', format=tarfile.USTAR_FORMAT) as archive:
        for name, (data, mode) in sorted(files.items()):
            member = tarfile.TarInfo(name)
            member.size, member.mode = len(data), mode
            member.uid = member.gid = member.mtime = 0
            archive.addfile(member, io.BytesIO(data))
    return output.getvalue()


def image_archive(binary, ca):
    layer = tar_bytes({'bin/ryuk': (binary, 0o755),
                       'etc/ssl/certs/ca-certificates.crt': (ca, 0o644)})
    diff = 'sha256:' + sha(layer)
    config = canonical({'architecture': 'amd64', 'os': 'linux',
        'created': '1970-01-01T00:00:00Z',
        'config': {'Cmd': ['/bin/ryuk'], 'WorkingDir': '/',
                   'Env': ['PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'],
                   'Labels': {'org.testcontainers.ryuk': 'true'}},
        'rootfs': {'type': 'layers', 'diff_ids': [diff]}})
    image_id = 'sha256:' + sha(config)
    reference = 'local/enterprise-shop-ryuk:sha256-' + sha(config)
    manifest = canonical([{'Config': sha(config) + '.json', 'RepoTags': [reference], 'Layers': ['layer.tar']}])
    archive = tar_bytes({sha(config) + '.json': (config, 0o644),
                         'manifest.json': (manifest, 0o644), 'layer.tar': (layer, 0o644)})
    return reference, image_id, config, layer, archive


def build(directory, analyze=True):
    require(not directory.exists(), 'Candidate directory exists: use --reuse to verify it, or choose a new --directory')
    directory.mkdir(parents=True)
    url = f'https://codeload.github.com/testcontainers/moby-ryuk/tar.gz/{SOURCE}'
    with urllib.request.urlopen(url, timeout=120) as response:
        data = response.read()
    require(sha(data) == ARCHIVE_SHA, 'Unreviewed upstream source archive')
    (directory / 'upstream.tar.gz').write_bytes(data)
    source = directory / 'source'
    source.mkdir()
    prefix = 'moby-ryuk-' + SOURCE + '/'
    with tarfile.open(fileobj=io.BytesIO(data)) as archive:
        for member in archive:
            require(member.name.startswith(prefix.rstrip('/')), 'Wrong source archive root')
            if not member.isfile():
                require(member.isdir(), 'Unexpected source link/device')
                continue
            relative = member.name.removeprefix(prefix)
            require(not Path(relative).is_absolute() and '..' not in Path(relative).parts, 'Unsafe source path')
            destination = source / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(archive.extractfile(member).read())
    patch = ROOT / '.github/security/ryuk/moby-client.patch'
    subprocess.run(['git', 'apply', '--check', str(patch)], cwd=source, check=True)
    subprocess.run(['git', 'apply', str(patch)], cwd=source, check=True)
    source_hashes = {p.relative_to(source).as_posix(): sha(p.read_bytes())
                     for p in sorted(source.rglob('*')) if p.is_file()}
    subprocess.run(['docker', 'pull', '--platform', 'linux/amd64', BUILDER], check=True)
    inspect = json.loads(subprocess.check_output(['docker', 'image', 'inspect', BUILDER]))[0]
    require(inspect['Os'] == 'linux' and inspect['Architecture'] == 'amd64'
            and BUILDER.split('@')[1] in [r.split('@')[1] for r in inspect['RepoDigests']], 'Wrong builder')
    command = ['docker', 'run', '--rm', '--platform', 'linux/amd64', '--user', f'{os.getuid()}:{os.getgid()}',
               '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
               '-v', f'{source.resolve()}:/source:ro', '-v', f'{directory.resolve()}:/evidence',
               '-v', 'ryuk-candidate-build:/tmp', '-w', '/source',
               '-e', 'GOTOOLCHAIN=local', '-e', 'CGO_ENABLED=0', '-e', 'GOOS=linux', '-e', 'GOARCH=amd64',
               '-e', 'GOAMD64=v1', '-e', 'GOPATH=/tmp/go', '-e', 'GOCACHE=/tmp/cache']
    certificate = os.environ.get('AUXILIARY_SCAN_CA_BUNDLE')
    if certificate:
        command += ['-v', f'{Path(certificate).resolve()}:/run/ca.pem:ro', '-e', 'SSL_CERT_FILE=/run/ca.pem']
    command += [BUILDER, 'sh', '-ec']
    flags = '-mod=readonly -buildvcs=false -a -installsuffix cgo -ldflags="-w -s" -trimpath'
    subprocess.run(command + [f'go mod verify; go build {flags} -o /evidence/ryuk .; '
        f'go build {flags} -o /evidence/ryuk.repeat .; '
        'go version -m /evidence/ryuk > /evidence/buildinfo.txt; '
        'go list -mod=readonly -deps -json . > /evidence/source-packages.json; '
        'go list -mod=readonly -m -json all > /evidence/source-modules.json; '
        'cp /etc/ssl/certs/ca-certificates.crt /evidence/ca-certificates.crt'], check=True)
    binary = (directory / 'ryuk').read_bytes()
    require(binary == (directory / 'ryuk.repeat').read_bytes(), 'Candidate binary build is not repeatable')
    require(source_hashes == {p.relative_to(source).as_posix(): sha(p.read_bytes())
                             for p in sorted(source.rglob('*')) if p.is_file()}, 'Build mutated reviewed source')
    tests = ryuk_client_tests.prepare(source, directory / 'tests')
    test_command = command.copy()
    test_command[test_command.index('-w') + 1] = '/evidence/tests'
    subprocess.run(test_command + ['gofmt -w *_test.go'], check=True)
    test_files = {p.name: sha(p.read_bytes()) for p in tests.iterdir() if p.is_file()}
    require(test_files == json.loads(ryuk_client_tests.CONTRACT.read_text())['source_files'], 'Unreviewed Ryuk test overlay')
    subprocess.run(test_command + ['go mod download github.com/stretchr/testify github.com/davecgh/go-spew '
        'github.com/pmezard/go-difflib github.com/stretchr/objx gopkg.in/yaml.v3; go mod verify'], check=True)
    with (directory / 'client-tests.jsonl').open('w') as out, (directory / 'client-tests.log').open('w') as err:
        status = subprocess.run(test_command[:3] + ['--network', 'none'] + test_command[3:] +
            ['go test -mod=readonly -count=1 -timeout=90s -json .'], stdout=out, stderr=err, timeout=120).returncode
    client_tests = {'status': status, 'source_files': test_files,
                    'output_sha256': sha((directory / 'client-tests.jsonl').read_bytes())}
    write(directory / 'client-test-receipt.json', client_tests)
    ryuk_client_tests.validate(tests, client_tests, sha)
    reference, image_id, config, layer, archive = image_archive(binary, (directory / 'ca-certificates.crt').read_bytes())
    (directory / 'image.tar').write_bytes(archive)
    (directory / 'image-config.json').write_bytes(config)
    (directory / 'layer.tar').write_bytes(layer)
    census = {name: {'sha256': sha(payload), 'size': len(payload)} for name, payload in
              [('bin/ryuk', binary), ('etc/ssl/certs/ca-certificates.crt', (directory / 'ca-certificates.crt').read_bytes())]}
    write(directory / 'filesystem-census.json', census)
    receipt = {'upstream_source_sha': SOURCE, 'upstream_archive_sha256': ARCHIVE_SHA,
        'patch_sha256': sha(patch.read_bytes()), 'source_files': source_hashes,
        'builder': BUILDER, 'builder_image_id': inspect['Id'], 'go_version': '1.26.8',
        'flags': flags, 'cgo_enabled': '0', 'upx': False, 'binary_sha256': sha(binary),
        'repeat_sha256': sha((directory / 'ryuk.repeat').read_bytes()),
        'image_reference': reference, 'image_id': image_id, 'archive_sha256': sha(archive),
        'layer_sha256': sha(layer), 'filesystem': census,
        'repository_head': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
        'govulncheck_version': TOOL, 'govulncheck_sum': TOOL_SUM, 'analysis': {}, 'client_tests': client_tests}
    receipt['evidence_hashes'] = {name: sha((directory / name).read_bytes())
        for name in ['buildinfo.txt', 'source-packages.json', 'source-modules.json']}
    write(directory / 'build-receipt.json', receipt)
    subprocess.run(['docker', 'load', '-i', str(directory / 'image.tar')], check=True)
    require(json.loads(subprocess.check_output(['docker', 'image', 'inspect', reference]))[0]['Id'] == image_id,
            'Loaded candidate configuration differs')
    if analyze:
        subprocess.run(command + [f'go install golang.org/x/vuln/cmd/govulncheck@{TOOL}; '
            'cp /tmp/go/bin/govulncheck /evidence/govulncheck; '
            'go version -m /evidence/govulncheck > /evidence/govulncheck-buildinfo.txt'], check=True)
        receipt['tool_sha256'] = sha((directory / 'govulncheck').read_bytes())
        receipt['evidence_hashes']['govulncheck-buildinfo.txt'] = sha((directory / 'govulncheck-buildinfo.txt').read_bytes())
        for mode, args in [('source', ['-mode=source', '-json', '.']), ('binary', ['-mode=binary', '-json', '/evidence/ryuk'])]:
            with (directory / f'{mode}-govulncheck.json').open('w') as out, (directory / f'{mode}-govulncheck.log').open('w') as err:
                status = subprocess.run(command[:-2] + ['/tmp/go/bin/govulncheck'] + args, stdout=out, stderr=err, timeout=600).returncode
            receipt['analysis'][mode] = {'status': status, 'sha256': sha((directory / f'{mode}-govulncheck.json').read_bytes())}
        write(directory / 'build-receipt.json', receipt)
        require(all(v['status'] == 0 for v in receipt['analysis'].values()), 'Candidate govulncheck failed')
    reviewed = ROOT / '.github/security/ryuk/candidate.json'
    if reviewed.is_file():
        import ryuk_candidate_evidence
        ryuk_candidate_evidence.validate_build(json.loads(reviewed.read_text()), directory,
                                               receipt['repository_head'], analyze)
    print(json.dumps(receipt, indent=2))
    return receipt


def reuse(directory, analyze=True):
    import ryuk_candidate_evidence
    candidate = json.loads((ROOT / '.github/security/ryuk/candidate.json').read_text())
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    _, receipt = ryuk_candidate_evidence.validate_build(candidate, directory, head, analyze)
    observed = json.loads(subprocess.check_output(['docker', 'image', 'inspect', candidate['image_reference']]))[0]
    require(observed['Id'] == candidate['image_id'], 'Local candidate configuration drift; rebuild before Maven')
    print('Verified existing local Ryuk source, repeat binaries, tests, archive and loaded image: ' + candidate['image_id'])
    return receipt


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, default=ROOT / '.tmp/ryuk-candidate')
    parser.add_argument('--no-analysis', action='store_true')
    parser.add_argument('--reuse', action='store_true', help='Revalidate existing current-HEAD evidence and loaded image')
    args = parser.parse_args()
    try:
        (reuse if args.reuse else build)(args.directory.resolve(), not args.no_analysis)
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, 'Ryuk preparation failed: ' + str(error) + '\n')
