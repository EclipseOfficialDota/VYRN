#!/usr/bin/env bash
# Local Linux harness: real isolated PostgreSQL, simulated Docker/Restic. No S3 or live Core changes.
set -euo pipefail
umask 077
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
mkdir -p "$root/.local/backup-tests"
work=$(mktemp -d "$root/.local/backup-tests/workflow.XXXXXXXX")
export VYRN_BACKUP_TEST_WORK="$work"
export VYRN_BACKUP_TEST_DB="vyrn_backup_fixture_$(date +%s)_$$"
created=0
cleanup() { result=$?; trap - EXIT; if [ "$created" = 1 ]; then runuser -u postgres -- dropdb --if-exists "$VYRN_BACKUP_TEST_DB"; fi; rm -rf -- "$work"; exit "$result"; }
trap cleanup EXIT
runuser -u postgres -- createdb "$VYRN_BACKUP_TEST_DB"
created=1
mkdir "$work/bin" "$work/source-media" "$work/state"
python3 - "$work" <<'PY'
import hashlib,pathlib,sys
root=pathlib.Path(sys.argv[1]); sql=['CREATE TABLE schema_migrations(version int);','CREATE TABLE users(id int);','CREATE TABLE messages(id int);','CREATE TABLE media(storage_key uuid,size bigint,sha256 text);','CREATE TABLE media_upload_parts(storage_key uuid,size int,sha256 text);']
for i,(table,data) in enumerate([('media',b'published-media-fixture'),('media_upload_parts',b'pending-part-fixture')],1):
    key=f'00000000-0000-0000-0000-{i:012d}';(root/'source-media'/key).write_bytes(data)
    sql.append(f"INSERT INTO {table} VALUES('{key}',{len(data)},'{hashlib.sha256(data).hexdigest()}');")
(root/'fixture.sql').write_text('\n'.join(sql))
PY
runuser -u postgres -- psql -d "$VYRN_BACKUP_TEST_DB" -v ON_ERROR_STOP=1 <"$work/fixture.sql" >/dev/null
cat >"$work/bin/docker" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
work=$VYRN_BACKUP_TEST_WORK
case "$1" in
inspect) if [[ "$*" == *State.Health* ]]; then if [[ ${FAIL_HEALTH:-0} == 1 ]]; then echo unhealthy; else echo healthy; fi; elif [[ "$*" == *State.Running* ]]; then cat "$work/running"; else echo fixture-image; fi; exit;;
cp) if [[ ${FAIL_COPY:-0} == 1 ]]; then exit 23; fi; tar -cf - -C "$work/source-media" .; exit;;
compose) shift; while [[ "$1" == --env-file || "$1" == -f ]]; do shift 2; done;;
*) exit 90;;
esac
case "$1" in
ps) echo aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;;
stop) echo false >"$work/running";;
start) echo true >"$work/running";;
exec)
 shift; [[ "$1" == -T ]]; shift; [[ "$1" == postgres ]]; shift
 program=$1; shift; args=("$@")
 for ((i=0;i<${#args[@]};i++)); do
   if [[ ${args[i]} == -U ]]; then args[i+1]=postgres; fi
   if [[ ${args[i]} == -d || ${args[i]} == --dbname ]]; then
     if [[ ${args[i+1]} == vyrn ]]; then args[i+1]=$VYRN_BACKUP_TEST_DB; fi
     [[ ${args[i+1]} == "$VYRN_BACKUP_TEST_DB" || ${args[i+1]} =~ ^vyrn_restore_[0-9]+_[0-9]+$ ]] || exit 91
   fi
 done
 if [[ "$program" == createdb || "$program" == dropdb ]]; then [[ ${args[-1]} =~ ^vyrn_restore_[0-9]+_[0-9]+$ ]] || exit 92; fi
 exec runuser -u postgres -- "$program" "${args[@]}";;
*) exit 93;;
esac
SH
cat >"$work/bin/restic" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$1" == backup ]]; then cp -a "${@: -1}" "$VYRN_BACKUP_TEST_WORK/snapshot"; echo backup >>"$VYRN_BACKUP_TEST_WORK/restic-calls"; fi
SH
printf '#!/bin/sh\nexit 0\n' >"$work/bin/logger"
chmod 700 "$work/bin/"*
export PATH="$work/bin:$PATH"
export VYRN_RUNTIME_ENV_FILE="$work/runtime.env"
export VYRN_BACKUP_STATE_DIR="$work/state"
export RESTIC_REPOSITORY=s3:https://fixture.invalid/vyrn
export RESTIC_PASSWORD_FILE="$work/password"
printf 'fixture-only\n' >"$work/password"
printf 'FIXTURE=1\n' >"$work/runtime.env"
echo true >"$work/running"
sh "$root/scripts/backup.sh"
[[ $(cat "$work/running") == true && -f "$work/state/last-success" ]]
restore_before=$(runuser -u postgres -- psql -d "$VYRN_BACKUP_TEST_DB" -At -c "SELECT count(*) FROM pg_database WHERE datname LIKE 'vyrn_restore_%';")
sh "$root/scripts/restore-smoke.sh" "$work/snapshot"
[[ $(runuser -u postgres -- psql -d "$VYRN_BACKUP_TEST_DB" -At -c "SELECT count(*) FROM pg_database WHERE datname LIKE 'vyrn_restore_%';") == "$restore_before" ]]
if FAIL_COPY=1 sh "$root/scripts/backup.sh"; then echo 'Copy failure was incorrectly accepted' >&2; exit 1; fi
[[ $(cat "$work/running") == true && -f "$work/state/last-failure" && $(wc -l <"$work/restic-calls") == 1 ]]
if FAIL_HEALTH=1 sh "$root/scripts/backup.sh"; then echo 'Unhealthy Core was incorrectly accepted' >&2; exit 1; fi
[[ -f "$work/state/last-failure" && $(wc -l <"$work/restic-calls") == 1 ]]
echo 'PASS: real isolated PostgreSQL dump/restore, published/staged media recovery, failed-copy resume; Docker/S3 simulated.'
