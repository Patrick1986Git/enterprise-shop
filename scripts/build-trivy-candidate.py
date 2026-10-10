#!/usr/bin/env python3
"""Build and independently verify the reviewed minimal Trivy source remediation."""
import argparse
import gzip
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
SOURCE = '591e9799316a602e703f0b484f6c6d7b234ec8f3'
ARCHIVE_SHA = '4a5bb1c16ccc55368843eb95ee9a25519a0bfef26609a8b4295f2060af43077e'
BUILDER = 'docker.io/library/golang:1.27.2-alpine3.24@sha256:85dc1069ac644ea3c527b177303a406eb3358192816cd7f9e5848eb658851673'
BUILDER_ID = 'sha256:0a761904b78c29eba4ec83bd83b7312db1e113dcd92da7fc669843ad9bca56a1'
BUILDER_MANIFEST = 'sha256:3b15ba438c60ab8fabe5fee3b11025c0049e358b31feff384648f11fc8e335f8'
BUILDER_BINARY = '548d3c32f83fa81152f543eb1526be7884cfe0526eb5e9ce29d3eca14786e7e0'
PATCH_SHA = '9fd37166f858e711be6df21fed4d716a7a98951c9a18cde3789cb743770f13c3'
GO_MOD_SHA = '54eb7f4afb4f86650b63d72deb206c73f24f5fbf1f20e93e93e380eba1ab11c5'
GO_SUM_SHA = 'f0792bca7d673d64f10e76910532f3a1252301ac8e7a0c68aebd31fd5cd4c3b1'
TOOL = 'v1.8.0'
TOOL_SUM = 'h1:clG4qBU6zH5VKjti8n5j8BBuYzoSha392xXMkXS351U='
SDK_SHA = 'ecbadb99091a3f46e31f5f934b068b1864eafa7995211b39eaddf76996045fe5'
SDK_TOOLS = {
    'bin/go': BUILDER_BINARY,
    'bin/gofmt': '5583dbb3339147fee7cdb067eb3cd4a50015b48c27084377f49d93833aa4ad77',
    'pkg/tool/linux_amd64/compile': '8d4484dbaa31c4c6cec965aadd42efaa1e8d59e4a30ca3b466b502c377c29121',
    'pkg/tool/linux_amd64/link': 'b7567a824e05fa24240edbf883e77e536914f22444c6946f1cd4a904b315f9c2'}
FLAGS = '-p=2 -mod=readonly -buildvcs=false -trimpath -ldflags="-s -w -X github.com/aquasecurity/trivy/pkg/version/app.ver=0.75.0"'
SPEC = importlib.util.spec_from_file_location('ryuk_builder', ROOT / 'scripts/build-ryuk-candidate.py')
RYUK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RYUK)
sha, require, write = RYUK.sha, RYUK.require, RYUK.write


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


def verify_compiler(directory):
    url = 'https://go.dev/dl/go1.27.2.linux-amd64.tar.gz'
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
    observed = json.loads(subprocess.check_output(['docker', 'image', 'inspect', BUILDER]))[0]
    require(observed['Id'] == BUILDER_ID and observed['Architecture'] == 'amd64' and observed['Os'] == 'linux'
            and any(r.endswith('@' + BUILDER.split('@')[1]) for r in observed['RepoDigests']), 'Wrong immutable builder')
    for name, digest in [('index', BUILDER.split('@')[1]), ('manifest', BUILDER_MANIFEST)]:
        raw = subprocess.check_output(['docker', 'buildx', 'imagetools', 'inspect', '--raw', BUILDER.split('@')[0] + '@' + digest])
        require('sha256:' + sha(raw) == digest, 'Builder registry identity differs')
        (directory / ('builder-' + name + '.json')).write_bytes(raw)
    require(json.loads((directory / 'builder-manifest.json').read_text())['config']['digest'] == BUILDER_ID,
            'Builder manifest/configuration mismatch')
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
        'builder_binary_sha256': BUILDER_BINARY, 'go_version': '1.27.2', 'flags': FLAGS,
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
    for mode, args in [('source', ['-mode=source', '-json', './cmd/trivy']),
                       ('binary', ['-mode=binary', '-json', '/evidence/trivy.first'])]:
        with (directory / f'{mode}-govulncheck.json').open('w') as out, (directory / f'{mode}-govulncheck.log').open('w') as err:
            status = subprocess.run(analyzer + [BUILDER, '/evidence/govulncheck'] + args,
                                    stdout=out, stderr=err, timeout=1800).returncode
        receipt['analysis'][mode] = {'status': status, 'sha256': sha((directory / f'{mode}-govulncheck.json').read_bytes())}
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
    require(json.loads(subprocess.check_output(['docker', 'image', 'inspect', reference]))[0]['Id'] == image_id,
            'Loaded image configuration mismatch')
    for name in ('first', 'repeat', 'analysis'):
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
    parser.add_argument('--directory', type=Path, default=ROOT / '.tmp/trivy-candidate')
    args = parser.parse_args()
    try:
        build(args.directory.resolve())
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        parser.exit(1, 'Trivy preparation failed: ' + str(error) + '\n')
