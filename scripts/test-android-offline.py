"""Private archive checksum/scope/revision checks; no downloads or native builds."""
import hashlib
import importlib.util
import io
from pathlib import Path
import subprocess
import tarfile
import tempfile
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('offline', Path(__file__).with_name('restore-android-offline.py'))
offline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(offline)

with tempfile.TemporaryDirectory() as directory:
    root = Path(directory)
    destination = root / '.local/offline'
    destination.mkdir(parents=True)
    archive = destination / 'vyrn-android-offline.tar.gz'

    def bundle(path, *, revision='a' * 40):
        with tarfile.open(archive, 'w:gz') as output:
            for name, content in (('.local/offline/source-commit.txt', revision.encode()), (path, b'cache')):
                member = tarfile.TarInfo(name)
                member.size = len(content)
                output.addfile(member, io.BytesIO(content))
        Path(str(archive) + '.sha256').write_text(hashlib.sha256(archive.read_bytes()).hexdigest() + '  ' + archive.name)

    for path in ('../escape', '/absolute', '.local/runtime.env', 'admin/app.js'):
        bundle(path)
        with patch.object(subprocess, 'check_output', return_value='a' * 40):
            try:
                offline.restore(root)
            except ValueError:
                pass
            else:
                raise AssertionError('Unsafe archive accepted: ' + path)
        assert not (root / '.tools').exists()
    bundle('.tools/android-sdk/platforms/android-36/android.jar', revision='b' * 40)
    with patch.object(subprocess, 'check_output', return_value='a' * 40):
        try:
            offline.restore(root)
        except ValueError as error:
            assert 'revision' in str(error)
        else:
            raise AssertionError('Mismatched source accepted')
    bundle('.tools/android-sdk/platforms/android-36/android.jar')
    with patch.object(subprocess, 'check_output', return_value='a' * 40):
        offline.restore(root)
    assert (root / '.tools/android-sdk/platforms/android-36/android.jar').read_bytes() == b'cache'
    member = tarfile.TarInfo('.tools/android-sdk/link')
    member.type = tarfile.SYMTYPE
    member.linkname = '../../.local/runtime.env'
    try:
        offline.cache_filter(member, str(root))
    except ValueError:
        pass
    else:
        raise AssertionError('Link to server settings accepted')
    archive.write_bytes(b'corrupt')
    try:
        offline.restore(root)
    except ValueError as error:
        assert 'checksum' in str(error)
    else:
        raise AssertionError('Corrupt archive accepted')
print('PASS: private cache checksum, path allowlist, source revision and restore')
