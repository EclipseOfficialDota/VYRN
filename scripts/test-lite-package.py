"""Offline packaging contract, using a fake Docker CLI; no daemon or network."""
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile

root = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory() as temporary:
    temp = Path(temporary)
    docker = temp / 'docker'
    docker.write_text('''#!/usr/bin/env python3
import os, pathlib, sys
args = sys.argv[1:]
state = pathlib.Path(os.environ['FAKE_DOCKER_STATE'])
if args[0] == 'pull':
    state.write_text(args[args.index('--platform')+1].split('/')[1])
elif args[:2] == ['image', 'inspect']:
    print(state.read_text())
elif args[:2] == ['image', 'save']:
    pathlib.Path(args[args.index('--output')+1]).write_bytes(b'packaging-test-only-not-an-image')
elif args[0] != 'tag':
    raise SystemExit('unexpected Docker operation')
''')
    docker.chmod(0o755)
    out = temp / 'output'
    revision = 'a' * 40
    subprocess.run(['bash', str(root/'scripts/package-offline.sh'), revision, str(out)],
                   env=dict(os.environ, PATH=str(temp)+':'+os.environ['PATH'], FAKE_DOCKER_STATE=str(temp/'arch')), check=True)
    assert len(list(out.glob('*.tar.gz'))) == 3
    for archive in out.glob('*.tar.gz'):
        subprocess.run(['sha256sum', '-c', archive.name+'.sha256'], cwd=out, check=True)
        with tarfile.open(archive) as bundle:
            names = bundle.getnames()
            assert not any('runtime.env' in name or '.git' in name for name in names)
            assert './deployment/compose.lite.yaml' in names
            assert './admin/index.html' in names
            if 'offline' in archive.name:
                assert './images.tar' in names
                arch = 'amd64' if 'amd64' in archive.name else 'arm64'
                assert (arch+'-'+revision) in bundle.extractfile('./deployment/compose.offline.yaml').read().decode()
                assert './SHA256SUMS' in names
            else:
                assert './images.tar' not in names
print('PASS: runtime and two architecture-specific offline bundles; whitelisted public files, image archive and checksums')
