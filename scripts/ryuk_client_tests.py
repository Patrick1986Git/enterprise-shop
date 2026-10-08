"""Select release Ryuk's mocked cleanup tests without porting its Docker build tests."""
import json
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CONTRACT = ROOT / '.github/security/ryuk/client-tests.json'


def prepare(source, directory):
    directory.mkdir()
    for name in ['config.go', 'consts.go', 'interfaces.go', 'main.go', 'reaper.go', 'go.mod', 'go.sum', 'config_test.go']:
        shutil.copyfile(source / name, directory / name)
    text = (source / 'reaper_test.go').read_text()
    # Preserve upstream helpers and all 14 mocked run scenarios; omit daemon/build tests.
    text = text[:text.index('func Test_newReaper(')] + text[text.index('// testConnect'):text.index('func TestAbortedClient(')]
    text += '// testID returns' + (source / 'reaper_test.go').read_text().split('// testID returns', 1)[1].split('// testClient returns', 1)[0]
    for line in ['\t"encoding/json"\n', '\t"strconv"\n', '\t"syscall"\n',
                 '\t"github.com/docker/docker/api/types"\n', '\t"github.com/docker/docker/api/types/build"\n',
                 '\t"github.com/docker/docker/api/types/filters"\n', '\t"github.com/docker/docker/pkg/jsonmessage"\n',
                 '\t"github.com/moby/go-archive"\n']:
        text = text.replace(line, '')
    text = text.replace('github.com/docker/docker/api/', 'github.com/moby/moby/api/')
    text = text.replace('github.com/docker/docker/client', 'github.com/moby/moby/client')
    text = text.replace('filters.Args', 'client.Filters').replace('filters.NewArgs()', 'make(client.Filters)')
    for old, new in [('container.ListOptions', 'client.ContainerListOptions'), ('network.ListOptions', 'client.NetworkListOptions'),
                     ('volume.ListOptions', 'client.VolumeListOptions'), ('image.ListOptions', 'client.ImageListOptions'),
                     ('volume.ListResponse', 'client.VolumeListResult'), ('Volumes:', 'Items:')]:
        text = text.replace(old, new)
    text = text.replace('cli.On("NegotiateAPIVersion", mockContext).Return()\n', '')
    text = text.replace('cli.On("Ping", mock.Anything).Return(types.Ping{}, tc.pingErr)',
                        'cli.On("Ping", mockContext, client.PingOptions{NegotiateAPIVersion: true}).Return(client.PingResult{}, tc.pingErr)')
    for number in ['1', '2']:
        text = text.replace(f'cli.On("NetworkRemove", mockContext, networkID{number}).',
                            f'cli.On("NetworkRemove", mockContext, networkID{number}, client.NetworkRemoveOptions{{}}).')
    text = text.replace('volumeRemoveForce).', 'client.VolumeRemoveOptions{Force: volumeRemoveForce}).')
    text = text.replace('[]*volume.Volume', '[]volume.Volume')
    text = text.replace('[]container.Port', '[]container.PortSummary')
    text = text.replace('{ID: networkID1, Created: tc.createdAt1}',
                        '{Network: network.Network{ID: networkID1, Created: tc.createdAt1}}')
    text = text.replace('{ID: networkID2, Created: tc.networkCreated2}',
                        '{Network: network.Network{ID: networkID2, Created: tc.networkCreated2}}')
    (directory / 'reaper_test.go').write_text(text)
    mock = (source / 'mock_test.go').read_text()
    mock = mock.replace('"github.com/docker/docker/api/types"', '"github.com/moby/moby/client"')
    mock = mock.replace('github.com/docker/docker/api/', 'github.com/moby/moby/api/')
    for kind in ['container', 'network', 'image']:
        title = kind.title()
        mock = mock.replace(f'options {kind}.ListOptions) ([]{kind}.Summary, error)',
                            f'options client.{title}ListOptions) (client.{title}ListResult, error)')
        mock = mock.replace(f'return args.Get(0).([]{kind}.Summary), args.Error(1)',
                            f'return client.{title}ListResult{{Items: args.Get(0).([]{kind}.Summary)}}, args.Error(1)')
    mock = mock.replace('options volume.ListOptions) (volume.ListResponse, error)',
                        'options client.VolumeListOptions) (client.VolumeListResult, error)')
    mock = mock.replace('args.Get(0).(volume.ListResponse)', 'args.Get(0).(client.VolumeListResult)')
    mock = mock.replace('\t"github.com/moby/moby/api/types/volume"\n', '')
    mock = mock.replace('options image.RemoveOptions) ([]image.DeleteResponse, error)',
                        'options client.ImageRemoveOptions) (client.ImageRemoveResult, error)')
    mock = mock.replace('return args.Get(0).([]image.DeleteResponse), args.Error(1)',
                        'return client.ImageRemoveResult{Items: args.Get(0).([]image.DeleteResponse)}, args.Error(1)')
    mock = mock.replace('options container.RemoveOptions) error',
                        'options client.ContainerRemoveOptions) (client.ContainerRemoveResult, error)')
    mock = mock.replace('networkID string) error',
                        'networkID string, options client.NetworkRemoveOptions) (client.NetworkRemoveResult, error)')
    mock = mock.replace('c.Called(ctx, networkID)', 'c.Called(ctx, networkID, options)')
    mock = mock.replace('force bool) error', 'options client.VolumeRemoveOptions) (client.VolumeRemoveResult, error)')
    mock = mock.replace('c.Called(ctx, volumeID, force)', 'c.Called(ctx, volumeID, options)')
    for kind in ['Container', 'Network', 'Volume']:
        start = mock.index(f'func (c *mockClient) {kind}Remove(')
        end = mock.index('\n}', start)
        part = mock[start:end].replace('return args.Error(0)', f'return client.{kind}RemoveResult{{}}, args.Error(0)')
        mock = mock[:start] + part + mock[end:]
    mock = mock[:mock.index('func (c *mockClient) NegotiateAPIVersion(')]
    mock = mock.replace('Ping(ctx context.Context) (types.Ping, error)',
                        'Ping(ctx context.Context, options client.PingOptions) (client.PingResult, error)')
    mock = mock.replace('c.Called(ctx)', 'c.Called(ctx, options)').replace('args.Get(0).(types.Ping)', 'args.Get(0).(client.PingResult)')
    (directory / 'mock_test.go').write_text(mock)
    shutil.copyfile(ROOT / 'scripts/ryuk-tests/client_contract_test.go', directory / 'client_contract_test.go')
    # These test-only dependencies already have reviewed checksums in release go.sum.
    mod = (directory / 'go.mod').read_text() + '\nrequire (\n'
    for module, version in [('github.com/davecgh/go-spew', 'v1.1.1'), ('github.com/pmezard/go-difflib', 'v1.0.0'),
                            ('github.com/stretchr/objx', 'v0.5.2'), ('gopkg.in/yaml.v3', 'v3.0.1')]:
        mod += f'\t{module} {version} // indirect\n'
    (directory / 'go.mod').write_text(mod + ')\n')
    return directory


def validate(directory, receipt, sha):
    contract = json.loads(CONTRACT.read_text())
    hashes = {p.name: sha(p.read_bytes()) for p in directory.iterdir() if p.is_file()}
    if type(receipt['status']) is not int or receipt['status'] != 0:
        raise ValueError('Ryuk client tests failed; inspect client-tests.jsonl and client-tests.log')
    if hashes != contract['source_files'] or receipt['source_files'] != hashes:
        raise ValueError('Ryuk client test source/status drift')
    events = [json.loads(line) for line in (directory.parent / 'client-tests.jsonl').read_text().splitlines()]
    passed = sorted(e['Test'] for e in events if e['Action'] == 'pass' and 'Test' in e)
    if passed != contract['passed_tests'] or any(e['Action'] in {'fail', 'skip'} for e in events):
        raise ValueError('Missing, failed or skipped Ryuk client contract tests')
    if receipt['output_sha256'] != sha((directory.parent / 'client-tests.jsonl').read_bytes()):
        raise ValueError('Ryuk client test evidence drift')
