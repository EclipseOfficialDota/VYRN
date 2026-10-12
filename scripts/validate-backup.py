#!/usr/bin/env python3
"""Verify snapshot files and every database-indexed media/part without extracting tar."""
import hashlib
import contextlib
import pathlib
import re
import sys
import tarfile

FILES = {"database.dump", "media.tar", "media-index.tsv", "project.yaml", "Caddyfile",
         "compose.yaml", "runtime.env", "core-image.txt", "backup-format"}
OPTIONAL = {"owner-access.json", "nginx-public.conf"}
UUID = re.compile(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
SHA = re.compile(r"[0-9a-f]{64}")


def digest(stream, output=None):
    checksum = hashlib.sha256()
    for block in iter(lambda: stream.read(1024 * 1024), b""):
        checksum.update(block)
        if output is not None:
            output.write(block)
    return checksum.hexdigest()


def validate(bundle, database_index=None, media_target=None):
    bundle = pathlib.Path(bundle)
    manifest = bundle / "checksums.sha256"
    if manifest.is_symlink() or not manifest.is_file():
        raise ValueError("invalid snapshot manifest")
    checked = set()
    required = FILES | {name for name in OPTIONAL if (bundle / name).exists() or (bundle / name).is_symlink()}
    for line in manifest.read_text().splitlines():
        checksum, name = line.split("  ", 1)
        file = bundle / name
        if name not in required or name in checked or not SHA.fullmatch(checksum) or file.is_symlink() or not file.is_file():
            raise ValueError("invalid snapshot manifest")
        with file.open("rb") as stream:
            if digest(stream) != checksum:
                raise ValueError("snapshot checksum mismatch")
        checked.add(name)
    if checked != required:
        raise ValueError("incomplete snapshot")
    if (bundle / "backup-format").read_text().strip() != "vyrn-backup-v2":
        raise ValueError("unsupported backup format")
    index = (bundle / "media-index.tsv").read_bytes()
    if database_index is not None and index != pathlib.Path(database_index).read_bytes():
        raise ValueError("restored database media index differs")
    expected = {}
    for row in index.decode().splitlines():
        key, length, checksum = row.split("\t")
        if not UUID.fullmatch(key) or not SHA.fullmatch(checksum) or not length.isdecimal() or not 0 < int(length) <= 250 * 1024 * 1024:
            raise ValueError("invalid media index")
        value = (int(length), checksum)
        if key in expected and expected[key] != value:
            raise ValueError("conflicting media index")
        expected[key] = value
    found, names = set(), set()
    target = pathlib.Path(media_target) if media_target is not None else None
    if target is not None:
        target.mkdir(mode=0o700)  # Never overwrite an existing destination.
    with tarfile.open(bundle / "media.tar", "r:") as archive:
        for member in archive:
            path = pathlib.PurePosixPath(member.name)
            if path.is_absolute() or ".." in path.parts or not (member.isfile() or member.isdir()):
                raise ValueError("unsafe media archive member")
            name = str(path)
            if name.startswith("media/"):
                name = name[6:]
            if name in names:
                raise ValueError("duplicate media archive path")
            names.add(name)
            if name in expected:
                length, checksum = expected[name]
                if not member.isfile() or member.size != length:
                    raise ValueError("media size mismatch")
                with archive.extractfile(member) as stream, (target / name).open("xb") if target is not None else contextlib.nullcontext() as stored:
                    if stored is not None:
                        (target / name).chmod(0o600)
                    if digest(stream, stored) != checksum:
                        raise ValueError("media checksum mismatch")
                found.add(name)
    if found != set(expected):
        raise ValueError("database-referenced media missing")
    return len(found)


if __name__ == "__main__":
    try:
        count = validate(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None, sys.argv[3] if len(sys.argv) > 3 else None)
        print(f"Snapshot verified: database/configuration checksums and {count} media/part files.")
    except Exception:
        sys.exit("Snapshot verification failed; no restore or backup should be accepted.")
