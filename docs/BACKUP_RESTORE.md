# Backup and restoration

There is currently **no external backup destination**. The local dump in `.local/backup-smoke/` is a temporary test artifact and is not a reliable backup.

`scripts/backup.sh` requires a Restic `s3:https://...` destination, TLS validation and a password file. Restic encrypts authenticated repository data before upload. S3 access values belong only in `/etc/vyrn/backup.env` mode 0600; keep another copy of the encryption password off the VPS. Do not put release keys in the backup source.

Initialize the selected external repository once with `restic init` after securely loading its environment. A daily systemd timer runs at 03:15 Asia/Tehran. The script takes a custom-format PostgreSQL dump, includes application/proxy/runtime configuration, performs a repository integrity check and retains seven daily and four weekly snapshots. `last-success`, `last-failure`, exit status and journal entries provide monitoring signals. Connect failure monitoring to an explicitly authorized alert destination before production acceptance.

The script currently covers the database and configuration. Add the private media volume to the encrypted backup set and verify its restoration before production, because database rows alone do not restore attachments.

## Restore procedure

1. Use `restic check`, identify the required snapshot and restore it into an isolated protected directory. Never print the runtime environment file.
2. Check the database dump checksum with `sha256sum -c database.sha256` in the restored directory.
3. Run `scripts/restore-smoke.sh /protected/path/database.dump`. It creates a separate temporary PostgreSQL database, restores with `--exit-on-error`, verifies schema/user/message tables and drops only that temporary database.
4. Verify media checksums and references once media backup is integrated. Rehearse service recovery with matching application and migration versions.
5. For real recovery, stop writes, restore into a new database and media volume, run acceptance checks, then switch configuration. Preserve the old volumes until recovery is verified.

## Evidence

A local PostgreSQL custom dump was restored into `vyrn_restore_smoke`, with two applied migrations and one bootstrap account; the temporary database was then dropped. This verifies the basic pg_dump/pg_restore path only. External transport, Restic encryption/repository restore, retention, media recovery, disaster recovery and failure alerts are **not yet verified**.
