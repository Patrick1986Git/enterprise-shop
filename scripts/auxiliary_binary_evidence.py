"""Verify exported image identities and measured UPX transformations as data."""
import hashlib
import gzip
import io
import json
import math
import os
import re
import shutil
import struct
import subprocess
import tarfile
import tempfile
import urllib.request
from pathlib import Path, PurePosixPath

PROVENANCE_POLICY = Path(__file__).resolve().parent.parent / '.github/security/auxiliary-container-scope.json'
MAX_METADATA_BYTES = 16 * 1024 * 1024
MAX_ARCHIVE_MEMBERS = 16384
MAX_LAYER_BYTES = 8 * 1024 ** 3
MAX_IMAGE_BYTES = 16 * 1024 ** 3
LAYER_ENCODINGS = {'application/vnd.oci.image.layer.v1.tar': False,
                   'application/vnd.oci.image.layer.v1.tar+gzip': True,
                   'application/vnd.docker.image.rootfs.diff.tar': False,
                   'application/vnd.docker.image.rootfs.diff.tar.gzip': True}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def archive_member(archive, name):
    require(isinstance(name, str), 'Invalid image evidence path')
    path = PurePosixPath(name)
    require(not path.is_absolute() and '..' not in path.parts, 'Unsafe image evidence path')
    for _ in archive:
        require(len(archive.members) <= MAX_ARCHIVE_MEMBERS, 'Too many image archive members')
    matches = [member for member in archive.getmembers() if member.name == name]
    require(len(matches) == 1 and matches[0].type in (tarfile.REGTYPE, tarfile.AREGTYPE),
            'Missing or ambiguous image evidence: ' + name)
    return matches[0]


def archive_bytes(archive, name):
    member = archive_member(archive, name)
    require(0 <= member.size <= MAX_METADATA_BYTES, 'Oversized image metadata')
    with archive.extractfile(member) as stream:
        data = stream.read(MAX_METADATA_BYTES + 1)
    require(len(data) == member.size, 'Incomplete image metadata')
    return data


def image_json(data):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, 'Duplicate image evidence field')
            result[key] = value
        return result
    def nonfinite(value):
        raise ValueError('Non-finite image JSON value: ' + value)
    def finite_float(value):
        number = float(value)
        require(math.isfinite(number), 'Non-finite image JSON number')
        return number
    return json.loads(data, object_pairs_hook=unique, parse_constant=nonfinite, parse_float=finite_float)


def descriptor_fields(descriptor):
    require(isinstance(descriptor, dict) and isinstance(descriptor.get('digest'), str)
            and re.fullmatch(r'sha256:[0-9a-f]{64}', descriptor['digest'])
            and type(descriptor.get('size')) is int and descriptor['size'] >= 0,
            'Invalid OCI descriptor digest/size')


def descriptor_bytes(archive, descriptor):
    descriptor_fields(descriptor)
    digest = descriptor['digest']
    require(isinstance(digest, str) and re.fullmatch(r'sha256:[0-9a-f]{64}', digest),
            'Invalid OCI descriptor digest')
    raw = archive_bytes(archive, 'blobs/sha256/' + digest.split(':')[1])
    require('sha256:' + sha(raw) == digest and type(descriptor['size']) is int
            and descriptor['size'] == len(raw), 'OCI descriptor digest/size mismatch')
    return raw


def configuration_metadata(archive, image_id):
    """Bind original config bytes to an inspected ID; return its raw metadata.

    Docker's containerd store reports the index/manifest digest as Id. Follow
    the original exported descriptor bytes; never reserialize configuration or
    infer its identity from tags, labels, or the export's rewritten index.
    """
    require(isinstance(image_id, str) and re.fullmatch(r'sha256:[0-9a-f]{64}', image_id),
            'Missing immutable image identity')
    metadata = {}
    def read(name):
        metadata[name] = archive_bytes(archive, name)
        return metadata[name]
    entries = image_json(read('manifest.json'))
    require(isinstance(entries, list) and len(entries) == 1, 'Ambiguous saved image')
    entry = entries[0]
    config = read(entry['Config'])
    config_id = 'sha256:' + sha(config)
    parsed = image_json(config)
    require(parsed['os'] == 'linux' and parsed['architecture'] == 'amd64'
            and parsed.get('variant', '') in ('', 'v1'), 'Unsupported image platform')
    layers, diffs = entry['Layers'], parsed['rootfs']['diff_ids']
    require(parsed['rootfs']['type'] == 'layers' and isinstance(layers, list)
            and isinstance(diffs, list) and len(layers) == len(diffs)
            and all(isinstance(d, str) and re.fullmatch(r'sha256:[0-9a-f]{64}', d) for d in diffs),
            'Missing or invalid image layer evidence')

    if image_id == config_id:
        return entry, config, None, metadata

    indexes = {'application/vnd.oci.image.index.v1+json',
               'application/vnd.docker.distribution.manifest.list.v2+json'}
    manifests = {'application/vnd.oci.image.manifest.v1+json',
                 'application/vnd.docker.distribution.manifest.v2+json'}

    def blob(descriptor):
        raw = descriptor_bytes(archive, descriptor)
        metadata['blobs/sha256/' + descriptor['digest'].split(':')[1]] = raw
        return raw

    require(image_json(read('oci-layout')) == {'imageLayoutVersion': '1.0.0'},
            'Missing OCI image layout')
    index = image_json(read('index.json'))
    require(type(index['schemaVersion']) is int and index['schemaVersion'] == 2, 'Invalid exported OCI index')
    roots = [d for d in index['manifests'] if d['digest'] == image_id]
    require(len(roots) == 1, 'Export does not contain the inspected immutable image')
    descriptor = roots[0]
    document = image_json(blob(descriptor))
    require(type(document['schemaVersion']) is int and document['schemaVersion'] == 2, 'Invalid OCI schema')
    if descriptor['mediaType'] in indexes:
        require(document.get('mediaType') == descriptor['mediaType'], 'OCI index media type mismatch')
        platforms = [d for d in document['manifests'] if d.get('platform', {}).get('os') == 'linux'
                     and d['platform'].get('architecture') == 'amd64'
                     and d['platform'].get('variant', '') in ('', 'v1')]
        require(len(platforms) == 1, 'Missing or ambiguous linux/amd64 OCI manifest')
        descriptor = platforms[0]
        document = image_json(blob(descriptor))
    require(descriptor['mediaType'] in manifests and type(document['schemaVersion']) is int
            and document['schemaVersion'] == 2
            and document.get('mediaType') == descriptor['mediaType'], 'Invalid OCI image manifest')
    require(document['config']['mediaType'] in {'application/vnd.oci.image.config.v1+json',
                'application/vnd.docker.container.image.v1+json'}
            and document['config']['digest'] == config_id and blob(document['config']) == config,
            'OCI manifest/configuration mismatch')
    require(len(document['layers']) == len(diffs), 'OCI layer census mismatch')
    for descriptor in document['layers']:
        descriptor_fields(descriptor)
        require(descriptor.get('mediaType') in LAYER_ENCODINGS, 'Unsupported OCI layer encoding')
    return entry, config, document, metadata


def stream_digest(stream):
    digest, size = hashlib.sha256(), 0
    while data := stream.read(1024 * 1024):
        size += len(data)
        require(size <= MAX_LAYER_BYTES, 'Oversized image layer')
        digest.update(data)
    return 'sha256:' + digest.hexdigest(), size


def layer_digests(archive, name):
    member = archive_member(archive, name)
    require(0 <= member.size <= MAX_LAYER_BYTES, 'Oversized image layer')
    with archive.extractfile(member) as stream:
        compressed = stream.read(2) == b'\x1f\x8b'
        stream.seek(0)
        raw, raw_size = stream_digest(stream)
        require(raw_size == member.size, 'Incomplete image layer')
        if compressed:
            stream.seek(0)
            with gzip.GzipFile(fileobj=stream) as decoded:
                diff, size = stream_digest(decoded)
        else:
            diff, size = raw, raw_size
    return raw, diff, raw_size, size, compressed


def saved_configuration(archive, image_id):
    """Verify both the descriptor/configuration chain and every exported layer."""
    entry, config, manifest, _ = configuration_metadata(archive, image_id)
    diffs = image_json(config)['rootfs']['diff_ids']
    checked, total = {}, 0
    for name, digest in zip(entry['Layers'], diffs):
        if name not in checked:
            checked[name] = layer_digests(archive, name)
        _, diff, _, size, _ = checked[name]
        total += size
        require(total <= MAX_IMAGE_BYTES, 'Oversized image filesystem')
        require(diff == digest, 'Saved image layer digest mismatch')
    for descriptor, digest in zip(manifest['layers'] if manifest else [], diffs):
        name = 'blobs/sha256/' + descriptor['digest'].split(':')[1]
        if name not in checked:
            checked[name] = layer_digests(archive, name)
        raw, diff, raw_size, _, compressed = checked[name]
        require(raw == descriptor['digest'] and raw_size == descriptor['size'], 'OCI layer digest/size mismatch')
        require(compressed == LAYER_ENCODINGS[descriptor['mediaType']], 'Unsupported OCI layer encoding')
        require(diff == digest, 'OCI layer/configuration mismatch')
    return config


def configuration_evidence(path, image_id, publisher_descriptor=None):
    """Read retained original metadata; layer/package checks have separate evidence."""
    with tarfile.open(path) as archive:
        _, config, manifest, metadata = configuration_metadata(archive, image_id)
        require({m.name for m in archive.getmembers()} == set(metadata)
                and len(archive.getmembers()) == len(metadata), 'Ambiguous retained image metadata')
        if publisher_descriptor is not None:
            require(manifest is not None and isinstance(publisher_descriptor, dict)
                    and set(publisher_descriptor) <= {'digest', 'mediaType', 'size'}
                    and publisher_descriptor.get('digest') == image_id,
                    'Publisher descriptor does not identify the original OCI image')
            original = metadata['blobs/sha256/' + image_id.split(':')[1]]
            require(publisher_descriptor.get('mediaType') == image_json(original)['mediaType'],
                    'Publisher descriptor media type differs from original bytes')
            if 'size' in publisher_descriptor:
                require(type(publisher_descriptor['size']) is int
                        and publisher_descriptor['size'] == len(original),
                        'Publisher descriptor size differs from original bytes')
    return 'sha256:' + sha(config)


def inspect_configuration(reference, expected=None, proof=None):
    if proof is not None:
        proof.unlink(missing_ok=True)
    observed = image_json(subprocess.check_output(['docker', 'image', 'inspect', reference], timeout=15))
    require(isinstance(observed, list) and len(observed) == 1, 'Ambiguous Docker image inspection')
    observed = observed[0]
    require(isinstance(observed['Id'], str) and re.fullmatch(r'sha256:[0-9a-f]{64}', observed['Id']),
            'Missing immutable Docker image identity')
    require(observed['Os'] == 'linux' and observed['Architecture'] == 'amd64', 'Unsupported Docker image platform')
    require(type(observed.get('Size')) is int and 0 <= observed['Size'] <= MAX_IMAGE_BYTES,
            'Invalid or oversized Docker image size')
    with tempfile.TemporaryDirectory(prefix='enterprise-shop-image-identity-') as temporary:
        require(shutil.disk_usage(temporary).free >= observed['Size'] + 64 * 1024 * 1024,
                'Insufficient temporary image storage')
        path = Path(temporary) / 'image.tar'
        subprocess.run(['docker', 'image', 'save', '-o', str(path), observed['Id']], check=True, timeout=90)
        require(path.stat().st_size <= MAX_IMAGE_BYTES, 'Oversized Docker image export')
        with tarfile.open(path) as archive:
            config_id = 'sha256:' + sha(saved_configuration(archive, observed['Id']))
            require(expected is None or config_id == expected, 'Loaded image configuration differs')
            if proof is not None:
                _, _, _, metadata = configuration_metadata(archive, observed['Id'])
                with tempfile.NamedTemporaryFile(dir=proof.parent, prefix='.identity-', delete=False) as staged:
                    staged_path = Path(staged.name)
                try:
                    with tarfile.open(staged_path, mode='w', format=tarfile.USTAR_FORMAT) as retained:
                        for name, raw in sorted(metadata.items()):
                            member = tarfile.TarInfo(name)
                            member.size, member.mode = len(raw), 0o644
                            retained.addfile(member, io.BytesIO(raw))
                    staged_path.replace(proof)
                finally:
                    staged_path.unlink(missing_ok=True)
    return observed, config_id


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


def provenance_identity(spec, image):
    reference = image.get('reference')
    require(isinstance(reference, str), 'Official compressed comparison: missing reference')
    parsed = re.fullmatch(r'docker\.io/testcontainers/ryuk:([0-9]+\.[0-9]+\.[0-9]+)@(sha256:[0-9a-f]{64})', reference)
    require(parsed is not None, 'Official compressed comparison: malformed or unsupported reference')
    reviewed = json.loads(PROVENANCE_POLICY.read_text())['comparisons']['official_ryuk']
    require(reference == reviewed['reference'] and parsed[1] == reviewed['version'],
            'Official compressed comparison: unreviewed version or image digest')
    expected = reviewed['evidence_transform']
    require(all(spec.get(key) == expected[key] for key in
                ('upstream_provenance', 'resolved_digest', 'source_commit')),
            'Official compressed comparison: unreviewed provenance identity')
    authority = expected['upstream_provenance']
    require(all(isinstance(value, str) and re.fullmatch(r'sha256:[0-9a-f]{64}', value)
                for value in (expected['resolved_digest'], authority['manifest_digest'], authority['statement_digest'])),
            'Official compressed comparison: malformed provenance digest')
    return parsed[2], authority


def provenance(spec, item, image):
    image_digest, authority = provenance_identity(spec, image)
    index_bytes = (item / 'registry-index.raw.json').read_bytes()
    require('sha256:' + sha(index_bytes) == image_digest, 'Upstream provenance image index digest mismatch')
    index = json.loads(index_bytes)
    require(any(x['digest'] == authority['manifest_digest'] for x in index['manifests']),
            'Upstream provenance is not bound to executed OCI index')
    registry = 'https://registry-1.docker.io/v2/testcontainers/ryuk/'
    token = json.load(urllib.request.urlopen(
        'https://auth.docker.io/token?service=registry.docker.io&scope=repository:testcontainers/ryuk:pull', timeout=60))['token']
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
