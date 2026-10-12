"""Offline checks: published and staged media restoration, corruption and path safety."""
import hashlib
import importlib.util
import io
import pathlib
import tarfile
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("backup", pathlib.Path(__file__).with_name("validate-backup.py"))
backup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(backup)
KEYS = ["00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002"]
FIXTURES = pathlib.Path(__file__).resolve().parents[1] / ".local" / "backup-tests"
FIXTURES.mkdir(parents=True, exist_ok=True)


class BackupValidation(unittest.TestCase):
    def bundle(self, root, entries=None):
        for name in backup.FILES:
            (root / name).write_bytes(b"fixture")
        (root / "backup-format").write_text("vyrn-backup-v2\n")
        data = [b"published media", b"pending upload part"]
        (root / "media-index.tsv").write_text("".join(f"{key}\t{len(body)}\t{hashlib.sha256(body).hexdigest()}\n" for key, body in zip(KEYS, data)))
        with tarfile.open(root / "media.tar", "w") as archive:
            for name, body in entries if entries is not None else zip(KEYS, data):
                row = tarfile.TarInfo(name); row.size = len(body)
                archive.addfile(row, io.BytesIO(body))
        self.seal(root)
        return data

    def seal(self, root):
        files = backup.FILES | {name for name in backup.OPTIONAL if (root / name).exists()}
        (root / "checksums.sha256").write_text("".join(f"{hashlib.sha256((root/name).read_bytes()).hexdigest()}  {name}\n" for name in sorted(files)))

    def test_optional_configuration_and_archive_links(self):
        with tempfile.TemporaryDirectory(dir=FIXTURES) as work:
            root = pathlib.Path(work); self.bundle(root)
            (root / "owner-access.json").write_bytes(b"fixture-only")
            self.seal(root); self.assertEqual(backup.validate(root), 2)
            (root / "owner-access.json").write_bytes(b"changed")
            with self.assertRaises(ValueError): backup.validate(root)
            self.bundle(root)
            with tarfile.open(root / "media.tar", "w") as archive:
                link = tarfile.TarInfo(KEYS[0]); link.type = tarfile.SYMTYPE; link.linkname = "../outside"
                archive.addfile(link)
            self.seal(root)
            with self.assertRaises(ValueError): backup.validate(root)

    def test_restore_published_and_pending_bytes(self):
        with tempfile.TemporaryDirectory(dir=FIXTURES) as work:
            root = pathlib.Path(work); data = self.bundle(root); target = root / "restored"
            self.assertEqual(backup.validate(root, root / "media-index.tsv", target), 2)
            self.assertEqual([(target / key).read_bytes() for key in KEYS], data)
            with self.assertRaises(FileExistsError): backup.validate(root, media_target=target)

    def test_corruption_and_database_index_rejected(self):
        with tempfile.TemporaryDirectory(dir=FIXTURES) as work:
            root = pathlib.Path(work); self.bundle(root)
            (root / "database.dump").write_bytes(b"changed")
            with self.assertRaises(ValueError): backup.validate(root)
            self.bundle(root); (root / "restored-index.tsv").write_text("")
            with self.assertRaises(ValueError): backup.validate(root, root / "restored-index.tsv")

    def test_missing_corrupt_duplicate_and_escaping_media(self):
        for entries in [[], [(KEYS[0], b"wrong bytes")], [(KEYS[0], b"published media"), (KEYS[0], b"published media")], [("../escape", b"bad")]]:
            with self.subTest(entries=entries), tempfile.TemporaryDirectory(dir=FIXTURES) as work:
                root = pathlib.Path(work); self.bundle(root, entries)
                with self.assertRaises(ValueError): backup.validate(root)


if __name__ == "__main__":
    unittest.main()
