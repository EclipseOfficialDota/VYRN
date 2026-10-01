"""Inspect tracked content without logging secret values. Uses only Python's standard library."""
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
BLOCKED_DIRS = {'.local', '.tools', 'backups', 'secrets', 'node_modules', '.gradle', '.cxx', 'build'}
BLOCKED_SUFFIXES = {'.jks', '.keystore', '.pem', '.key', '.p12', '.pfx', '.p8', '.dump', '.age', '.gpg', '.exe', '.apk', '.aab'}
PATTERNS = (
    re.compile(rb'-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----'),
    re.compile(rb'\bgh[pousr]_[A-Za-z0-9]{36,}\b'),
    re.compile(rb'\bgithub_pat_[A-Za-z0-9_]{40,}\b'),
    re.compile(rb'\bAKIA[0-9A-Z]{16}\b'),
)


def main():
    names = subprocess.check_output(['git', 'ls-files', '-z'], cwd=ROOT).split(b'\0')
    failures = []
    checked = 0
    for raw in names:
        if not raw:
            continue
        name = raw.decode('utf-8')
        relative = PurePosixPath(name)
        path = ROOT / name
        if path.is_symlink() or not path.resolve().is_relative_to(ROOT):
            failures.append((name, 'symlink or path outside repository'))
            continue
        if not path.is_file():
            failures.append((name, 'tracked content missing'))
            continue
        lower = relative.name.lower()
        blocked = bool(set(relative.parts) & BLOCKED_DIRS) or name.startswith('android/upstream/')
        blocked |= relative.suffix.lower() in BLOCKED_SUFFIXES
        blocked |= (lower == '.env' or lower.startswith('.env.')) and lower != '.env.example'
        blocked |= lower == 'google-services.json' or lower.startswith(('credentials', 'credential-'))
        blocked |= 'private-key' in lower
        if blocked:
            failures.append((name, 'forbidden tracked path'))
            continue
        if path.stat().st_size >= 100 * 1024 * 1024:
            failures.append((name, 'file exceeds GitHub blob limit'))
            continue
        if any(pattern.search(path.read_bytes()) for pattern in PATTERNS):
            failures.append((name, 'possible secret; inspect privately'))
        checked += 1
    for name, reason in failures:
        print(f'{name}: {reason}', file=sys.stderr)
    if failures:
        return 1
    print(f'PASS: {checked} tracked files; no forbidden paths or recognized secret patterns.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
