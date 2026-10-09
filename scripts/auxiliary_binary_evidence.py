"""Measured UPX transformation of copied image bytes; never execute the payload."""
import hashlib
import json
import os
import re
import struct
import subprocess
import tarfile
import urllib.request
from pathlib import Path


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def write(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def elf(data):
    require(len(data) >= 64 and data[:7] == b'\x7fELF\x02\x01\x01'
            and struct.unpack_from('<HH', data, 16) == (2, 62),
            'Expected nonempty Linux/amd64 ELF executable')


def buildinfo(text):
    lines = text.splitlines()
    require(lines and re.search(r': go1\.\d+\.\d+$', lines[0]), 'Missing Go build metadata')
    result = {'go_version': lines[0].rsplit(': go', 1)[1], 'dependencies': {}, 'build': {}}
    for line in lines[1:]:
        fields = line.strip().split('\t')
        if fields[0] == 'path':
            require(len(fields) == 2 and 'path' not in result, 'Malformed Go path')
            result['path'] = fields[1]
        elif fields[0] == 'mod':
            require(len(fields) in (3, 4) and 'main_module' not in result, 'Malformed main module')
            result['main_module'], result['main_version'] = fields[1:3]
        elif fields[0] == 'dep':
            require(len(fields) == 4 and fields[1] not in result['dependencies']
                    and fields[2].startswith('v') and fields[3].startswith('h1:'), 'Malformed embedded dependency')
            result['dependencies'][fields[1]] = {'version': fields[2], 'sum': fields[3]}
        elif fields[0] == 'build':
            key, value = fields[1].split('=', 1)
            require(key not in result['build'], 'Duplicate build setting')
            result['build'][key] = value
        else:
            raise ValueError('Unsupported or replaced embedded module metadata')
    require(result.get('main_module') == result.get('path') and result['dependencies'], 'Missing embedded modules')
    require(all(result['build'].get(k) == v for k, v in
                {'CGO_ENABLED': '0', 'GOARCH': 'amd64', 'GOOS': 'linux', '-buildmode': 'exe'}.items()),
            'Wrong embedded architecture/CGO/build mode')
    return result


def source_match(metadata, mod, sums):
    require(re.search(r'^module ' + re.escape(metadata['main_module']) + r'\s*$', mod, re.M),
            'Upstream source/main module mismatch')
    declared = dict(re.findall(r'^\s*([^\s()]+) (v\S+)(?:\s+// indirect)?\s*$', mod, re.M))
    checksums = {tuple(line.split()) for line in sums.splitlines()}
    require(all(declared.get(name) == dep['version'] and
                (name, dep['version'], dep['sum']) in checksums
                for name, dep in metadata['dependencies'].items()), 'Upstream source/module mismatch')


def download(url, digest, destination):
    data = urllib.request.urlopen(url, timeout=60).read(32 * 1024 * 1024 + 1)
    require(len(data) <= 32 * 1024 * 1024 and sha(data) == digest, 'Downloaded evidence/tool digest mismatch')
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(data)
    return data


def provenance(spec, item, image):
    authority = spec['upstream_provenance']
    index = json.loads((item / 'registry-index.raw.json').read_bytes())
    require(any(x['digest'] == authority['manifest_digest'] for x in index['manifests']),
            'Upstream provenance is not bound to executed OCI index')
    repository = image['reference'].removeprefix('docker.io/').split(':', 1)[0]
    require(image['reference'].startswith('docker.io/'), 'Unreviewed provenance registry')
    registry = 'https://registry-1.docker.io/v2/' + repository + '/'
    token = json.load(urllib.request.urlopen(
        'https://auth.docker.io/token?service=registry.docker.io&scope=repository:' + repository + ':pull', timeout=60))['token']
    def get(path, digest):
        request = urllib.request.Request(registry + path, headers={'Authorization': 'Bearer ' + token,
            'Accept': 'application/vnd.oci.image.manifest.v1+json'})
        data = urllib.request.urlopen(request, timeout=60).read(1024 * 1024)
        require('sha256:' + sha(data) == digest, 'Upstream provenance digest mismatch')
        return data
    manifest = get('manifests/' + authority['manifest_digest'], authority['manifest_digest'])
    (item / 'upstream-provenance-manifest.json').write_bytes(manifest)
    require(any(x['digest'] == authority['statement_digest'] for x in json.loads(manifest)['layers']),
            'Missing upstream provenance statement layer')
    statement = get('blobs/' + authority['statement_digest'], authority['statement_digest'])
    (item / 'upstream-provenance.json').write_bytes(statement)
    check_provenance(spec, item)


def check_provenance(spec, item):
    authority = spec['upstream_provenance']
    require('sha256:' + sha((item / 'upstream-provenance-manifest.json').read_bytes()) == authority['manifest_digest']
            and 'sha256:' + sha((item / 'upstream-provenance.json').read_bytes()) == authority['statement_digest'],
            'Wrong upstream provenance bytes')
    statement = json.loads((item / 'upstream-provenance.json').read_text())
    require(statement['predicateType'] == 'https://slsa.dev/provenance/v0.2'
            and any(x['digest'].get('sha256') == spec['resolved_digest'].split(':')[1] for x in statement['subject']),
            'Wrong upstream image provenance subject')
    predicate = statement['predicate']
    vcs = predicate['metadata']['https://mobyproject.org/buildkit@v1#metadata']['vcs']
    require(vcs['revision'] == spec['source_commit'] and vcs['source'] == authority['repository']
            and predicate['builder']['id'] == authority['builder'], 'Upstream provenance/source mismatch')
    # This is digest-bound, unsigned BuildKit metadata. It does not authenticate its claimed signer/builder.


def collect(image, tool, archive, item, analysis_image):
    spec = image['evidence_transform']
    files = {}
    manifest = json.loads((item / 'registry-manifest.json').read_text())
    with tarfile.open(archive) as saved:
        entries = json.load(saved.extractfile('manifest.json'))
        require(len(entries) == 1, 'Ambiguous image archive')
        entry = entries[0]
        config = saved.extractfile(entry['Config']).read()
        require('sha256:' + sha(config) == spec['image_id'], 'Archive configuration mismatch')
        (item / 'image-config.json').write_bytes(config)
        require(len(entry['Layers']) == len(manifest['layers']), 'Archive layer census mismatch')
        for name, expected in zip(entry['Layers'], manifest['layers']):
            # Reviewed scratch layers are uncompressed; digest binds every file to the OCI image.
            require(expected['mediaType'] == 'application/vnd.oci.image.layer.v1.tar', 'Unexpected scratch layer encoding')
            data = saved.extractfile(name).read()
            require('sha256:' + sha(data) == expected['digest'], 'Archived layer digest mismatch')
            import io
            with tarfile.open(fileobj=io.BytesIO(data)) as layer:
                for member in layer:
                    require(member.isdir() or member.isfile(), 'Unexpected scratch filesystem member')
                    if member.isfile():
                        require(member.name not in files, 'Ambiguous scratch filesystem')
                        files[member.name] = layer.extractfile(member).read()
    census = {name: {'sha256': sha(data), 'size': len(data)} for name, data in files.items()}
    require(census == spec['filesystem'], 'Unexpected scratch filesystem census')
    write(item / 'filesystem-census.json', census)
    compressed = files[image['identity']['binary'].lstrip('/')]
    elf(compressed)
    require(spec['upx_marker'].encode() in compressed and b'UPX!' in compressed, 'Missing reviewed UPX identification')
    (item / 'compressed.bin').write_bytes(compressed)
    asset = item / 'tools/upx.tar.xz'
    download(tool['url'], tool['archive_sha256'], asset)
    with tarfile.open(asset) as release:
        binary = release.extractfile(tool['member']).read()
    require(sha(binary) == tool['binary_sha256'], 'Decompressor executable digest mismatch')
    elf(binary)
    executable = item / 'tools/upx'
    executable.write_bytes(binary)
    executable.chmod(0o755)
    output = item / 'decompression'
    output.mkdir(parents=True, exist_ok=True)
    for name in ('unpacked', 'repeat'):
        (output / name).unlink(missing_ok=True)
    command = ['docker', 'run', '--rm', '--platform', 'linux/amd64', '--network', 'none', '--read-only',
               '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges', '--pids-limit', '64',
               '--memory', '512m', '--cpus', '2', '--user', f'{os.getuid()}:{os.getgid()}',
               '-v', f'{executable.resolve()}:/tools/upx:ro', '-v', f'{(item / "compressed.bin").resolve()}:/input/compressed:ro',
               '-v', f'{output.resolve()}:/payload', analysis_image]
    status = {}
    for name, args in [('version', ['/tools/upx', '--version']),
                       ('list', ['/tools/upx', '-l', '/input/compressed']),
                       ('test', ['/tools/upx', '-t', '/input/compressed']),
                       ('decompress', ['/tools/upx', '-d', '-o', '/payload/unpacked', '/input/compressed']),
                       ('repeat', ['/tools/upx', '-d', '-o', '/payload/repeat', '/input/compressed']),
                       ('buildinfo', ['go', 'version', '-m', '/payload/unpacked'])]:
        with (item / (name + '.txt')).open('w') as log:
            status[name] = subprocess.run(command + args, stdout=log, stderr=subprocess.STDOUT, timeout=120).returncode
        require(status[name] == 0, 'UPX/Go evidence transformation failure: ' + name)
    require((item / 'version.txt').read_text().splitlines()[0] == 'upx ' + tool['version'], 'Wrong UPX version')
    payload = (output / 'unpacked').read_bytes()
    elf(payload)
    require(sha(payload) == spec['decompressed_sha256'] == sha((output / 'repeat').read_bytes()),
            'Changed/nondeterministic decompressed binary')
    require(sha((item / 'compressed.bin').read_bytes()) == image['identity']['sha256'], 'Original compressed bytes changed')
    (output / 'repeat').unlink()
    target = item / 'analysis' / image['identity']['binary'].lstrip('/')
    target.parent.mkdir(parents=True, exist_ok=True)
    (output / 'unpacked').replace(target)
    target.chmod(0o755)  # Trivy requires executable mode; never run this payload.
    metadata = buildinfo((item / 'buildinfo.txt').read_text())
    require(metadata == spec['buildinfo'], 'Unexpected recovered Go build metadata')
    upstream = item / 'upstream'
    for name, digest in spec['source_files'].items():
        download(spec['source_url'] + '/' + spec['source_commit'] + '/' + name, digest, upstream / name)
    source_match(metadata, (upstream / 'go.mod').read_text(), (upstream / 'go.sum').read_text())
    provenance(spec, item, image)
    receipt = {'method': spec['method'], 'executed_image': image['reference'],
               'compressed_sha256': sha(compressed), 'decompressed_sha256': sha(payload),
               'tool': tool, 'analysis_image': analysis_image, 'statuses': status,
               'buildinfo': metadata, 'filesystem': census,
               'upx_marker': spec['upx_marker'], 'payload_executed': False}
    write(item / 'transformation.json', receipt)
    return item / 'analysis'


def validate(image, evidence, report, bom, directory):
    spec = image['evidence_transform']
    require(directory is not None, 'Measured transformation files required')
    receipt = json.loads((directory / 'transformation.json').read_text())
    require(receipt['method'] == spec['method'] == 'upx-deterministic-go-readback'
            and receipt['executed_image'] == image['reference'] and receipt['payload_executed'] is False,
            'Analysis payload cannot become the execution image')
    require(receipt['tool'] == spec['tool'] and receipt['analysis_image'] == spec['analysis_image'],
            'Unreviewed decompressor authority')
    require(all(type(receipt['statuses'][k]) is int and receipt['statuses'][k] == 0 for k in
                ('version', 'list', 'test', 'decompress', 'repeat', 'buildinfo')), 'UPX validation/decompression failure')
    require((directory / 'version.txt').read_text().splitlines()[0] == 'upx ' + spec['tool']['version'], 'Wrong UPX version')
    require(sha((directory / 'tools/upx').read_bytes()) == spec['tool']['binary_sha256']
            and sha((directory / 'tools/upx.tar.xz').read_bytes()) == spec['tool']['archive_sha256'],
            'Decompressor integrity mismatch')
    compressed = (directory / 'compressed.bin').read_bytes()
    target = image['identity']['binary'].lstrip('/')
    payload = (directory / 'analysis' / target).read_bytes()
    elf(compressed)
    elf(payload)
    require(sha(compressed) == receipt['compressed_sha256'] == image['identity']['sha256'], 'Wrong compressed binary hash')
    require(sha(payload) == receipt['decompressed_sha256'] == spec['decompressed_sha256'], 'Tampered decompressed payload')
    require(spec['upx_marker'].encode() in compressed and receipt['upx_marker'] == spec['upx_marker'], 'Wrong UPX identification')
    require(receipt['filesystem'] == spec['filesystem'] == json.loads((directory / 'filesystem-census.json').read_text()),
            'Unexpected filesystem census')
    require(evidence['image_id'] == spec['image_id'] and evidence['resolved_digest'] == spec['resolved_digest']
            and 'sha256:' + sha((directory / 'image-config.json').read_bytes()) == spec['image_id'],
            'Wrong executed image configuration/manifest')
    require('sha256:' + sha((directory / 'registry-index.raw.json').read_bytes()) == image['reference'].split('@')[1]
            and 'sha256:' + sha((directory / 'registry-manifest.raw.json').read_bytes()) == spec['resolved_digest'],
            'Wrong executed registry index/platform manifest')
    check_provenance(spec, directory)
    metadata = buildinfo((directory / 'buildinfo.txt').read_text())
    require(metadata == spec['buildinfo'] == receipt['buildinfo'], 'Wrong recovered Go toolchain/modules')
    for name, digest in spec['source_files'].items():
        require(sha((directory / 'upstream' / name).read_bytes()) == digest, 'Upstream source identity mismatch')
    source_match(metadata, (directory / 'upstream/go.mod').read_text(), (directory / 'upstream/go.sum').read_text())
    require(receipt['raw_sha256'] == sha((directory / 'raw.json').read_bytes())
            and receipt['sbom_sha256'] == sha((directory / 'sbom.cdx.json').read_bytes()), 'Analysis report binding mismatch')
    require(report['ArtifactType'] == 'filesystem' and report['ArtifactName'] == '/analysis'
            and not report.get('Metadata', {}).get('ImageID')
            and bom['metadata']['component']['name'] == '/analysis', 'Wrong decompressed analysis representation')
    results = report.get('Results', [])
    require(len(results) == 1 and results[0]['Target'] == target and results[0]['Type'] == 'gobinary',
            'Missing executable package readback: ' + target)
    expected = {(name, dep['version']) for name, dep in metadata['dependencies'].items()}
    expected |= {(metadata['main_module'], '' if metadata['main_version'] == '(devel)' else metadata['main_version']),
                 ('stdlib', 'v' + metadata['go_version'])}
    require({(p['Name'], p.get('Version', '')) for p in results[0]['Packages']} == expected,
            'Missing/wrong recovered stdlib or embedded module identity')
    require(type(receipt['govulncheck_status']) is int and receipt['govulncheck_status'] in (0, 3)
            and receipt['govulncheck_sha256'] == sha((directory / 'govulncheck.json').read_bytes()),
            'Binary govulncheck evidence failure')
    # govulncheck emits a stream of potentially pretty-printed JSON objects.
    text = (directory / 'govulncheck.json').read_text().strip()
    messages = []
    while text:
        message, end = json.JSONDecoder().raw_decode(text)
        messages.append(message)
        text = text[end:].lstrip()
    require(any(m.get('config', {}).get('scan_mode') == 'binary' for m in messages), 'Missing binary govulncheck configuration')
    sboms = [m['SBOM'] for m in messages if 'SBOM' in m]
    require(len(sboms) == 1 and sboms[0]['go_version'] == 'go' + metadata['go_version']
            and sboms[0]['roots'] == [metadata['main_module']], 'Wrong binary govulncheck Go identity')
    govuln_modules = {(m['path'], m.get('version', '')) for m in sboms[0]['modules']}
    require(all((name, dep['version']) in govuln_modules for name, dep in metadata['dependencies'].items()),
            'Missing binary govulncheck module identity')
    return metadata['main_module'], receipt
