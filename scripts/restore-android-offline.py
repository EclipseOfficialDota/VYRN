"""Restore an owner-supplied, checksummed Linux build cache, never server settings."""
import hashlib
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[1]
ALLOWED = ('.tools/android-sdk', '.local/gradle/caches', '.local/gradle/wrapper',
           'android/upstream', '.local/offline/source-commit.txt')


def cache_filter(member, destination):
    member = tarfile.data_filter(member, destination)
    root = Path(destination).resolve()
    paths = [root / member.name]
    if member.issym():
        paths.append(paths[0].parent / member.linkname)
    elif member.islnk():
        paths.append(root / member.linkname)
    for path in paths:
        name = path.resolve().relative_to(root).as_posix()
        if not any(name == base or name.startswith(base + '/') for base in ALLOWED):
            raise ValueError('Offline archive path or link escapes the build cache.')
    return member


def restore(root):
    archive = root / '.local/offline/vyrn-android-offline.tar.gz'
    expected = Path(str(archive) + '.sha256').read_text().split()[0]
    digest = hashlib.sha256()
    with archive.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    if digest.hexdigest() != expected:
        raise ValueError('Offline archive checksum mismatch; nothing extracted.')
    with tarfile.open(archive, 'r:gz') as bundle:
        members = bundle.getmembers()
        for item in members:
            name = str(PurePosixPath(item.name))
            if (item.name.startswith('/') or '..' in PurePosixPath(item.name).parts or
                    not any(name == base or name.startswith(base + '/') for base in ALLOWED)):
                raise ValueError('Unexpected path in offline archive; nothing extracted.')
            if item.isdev() or item.isfifo():
                raise ValueError('Special files are not allowed in offline archive.')
            # Validate links against the actual destination before any extraction.
            cache_filter(item, str(root))
        commit_file = bundle.extractfile('.local/offline/source-commit.txt')
        if commit_file is None:
            raise ValueError('Offline archive has no source revision.')
        commit = commit_file.read(128).decode().strip()
        current = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
        if commit != current:
            raise ValueError('Offline kit source revision differs from this checkout. Use the matching VYRN source revision.')
        if (root / 'android/upstream').exists():
            raise ValueError('Existing upstream checkout preserved. Restore the offline kit into a fresh checkout.')
        if sum(item.size for item in members if item.isfile()) > shutil.disk_usage(root).free:
            raise ValueError('Insufficient free space to restore offline build kit.')
        bundle.extractall(root, members=members, filter=cache_filter)
    print('Private Android build cache restored. System packages and server data were not changed.')


if __name__ == '__main__':
    try:
        restore(ROOT)
    except (OSError, ValueError, tarfile.TarError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error))
