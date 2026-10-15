# Backup and restoration

There is currently **no external backup destination**. Local dumps and the October15 snapshots under `/opt/vyrn/operations/` are temporary same-host artifacts and are not reliable disaster backups.

`scripts/backup.sh` requires a Restic `s3:https://...` destination, TLS validation and a password file. Restic encrypts authenticated repository data before upload. S3 access values belong only in `/etc/vyrn/backup.env` mode 0600; keep another copy of the encryption password off the VPS. Do not put release keys in the backup source.

Initialize the selected external repository once with `restic init` after securely loading its environment. A daily systemd timer runs at 03:15 Asia/Tehran. The script takes a custom-format PostgreSQL dump, includes application/proxy/runtime configuration, performs a repository integrity check and retains seven daily and four weekly snapshots. `last-success`, `last-failure`, exit status and journal entries provide monitoring signals. Connect failure monitoring to an explicitly authorized alert destination before production acceptance.

The v2 script stops Core writers while capturing the database and private media volume, including staged uploads, then restarts Core before external upload. It includes configuration, a media index, image identity and SHA256 checksums. The validator checks archive paths, indexed sizes/checksums and restored database references. Staging requires enough free disk for the media archive; Core is briefly unavailable during capture. Protected staging is removed on exit. External encrypted transport and restoration still need a real destination and rehearsal.

## Restore procedure

1. Use `restic check`, identify the required snapshot and restore it into an isolated protected directory. Never print the runtime environment file.
2. Run `python3 scripts/validate-backup.py /protected/restored-snapshot` to validate the v2 bundle before restoration. Keep runtime.env and owner-access.json private.
3. Run `scripts/restore-smoke.sh /protected/restored-snapshot`. It creates a separate temporary PostgreSQL database, restores with `--exit-on-error`, validates restored media references and extracts media into isolated scratch space. It cleans up its own temporary database and scratch files. Legacy database.dump input remains supported but explicitly does not verify media recovery.
4. Rehearse service startup with the restored database, media ownership and matching application/migration versions in an isolated network. Validate readiness and attachment integrity before any production switch.
5. For real recovery, stop writes, restore into a new database and media volume, run acceptance checks, then switch configuration. Preserve the old volumes until recovery is verified.

## Evidence

On2026-10-15, real VPS snapshots were validated and restored into disposable PostgreSQL databases and private media volumes. Candidate e66e6a8 upgraded a restored schema7 to schema9 and passed readiness, UID10001 ownership, and the single existing avatar's size/checksum checks. A fresh snapshot was taken immediately before the successful production deployment at `/opt/vyrn/operations/deploy-20261015-e66e6a8/snapshot`. Its parent directory is root-only and copied runtime.env is mode0600. Temporary databases, containers and volumes were cleaned up.

A second isolated drill restored that pre-deploy snapshot, upgraded its clone to schema9, and started the retained previous Core image successfully on the clone. Production stayed on e66e6a8. This establishes restored schema/media startup and old-binary readiness compatibility for the tested state; it does not establish every old API contract or full-host disaster recovery. External transport, actual Restic repository encryption/restore, retention, offsite recovery, Nginx/TLS host reconstruction and failure alerts remain **not verified**. One avatar does not establish recovery under a large image/voice/staged-upload workload.
