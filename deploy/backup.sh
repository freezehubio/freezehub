#!/bin/bash
# Nightly PostgreSQL dump to S3 (FZ-155, D-35).
#
# A single box has no automated snapshots and no point-in-time recovery. This is the whole
# of the backup story, which is why the story it belongs to is not done when this runs --
# it is done when a restore has been PERFORMED. See docs/14-operations.md.
#
# Installed by FZ-154 and run from a systemd timer.
set -euo pipefail

BUCKET="${BACKUP_BUCKET:?BACKUP_BUCKET is required}"
REGION="${AWS_REGION:?AWS_REGION is required}"
STAMP="$(date -u +%Y-%m-%dT%H-%M-%SZ)"
KEY="postgres/freezehub-${STAMP}.sql.gz"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

fail() {
  # A silent failure is the same as no backup, and worse because it looks like one.
  # This is what puts it somewhere a person will actually see.
  echo "BACKUP FAILED: $*" >&2
  logger -t freezehub-backup -p user.err "backup failed: $*" || true
  exit 1
}

cd /opt/freezehub

# --clean --if-exists so the dump can be replayed into a database that already has a
# schema, which is what a restore drill actually does.
docker compose exec -T postgres \
  pg_dump --username freezehub --dbname freezehub --clean --if-exists \
  > "$TMP/dump.sql" || fail "pg_dump returned non-zero"

# An empty or truncated dump uploads perfectly happily and restores into nothing, so the
# size is checked before it is trusted. 1 KiB is far below a real schema and far above
# empty.
SIZE=$(wc -c < "$TMP/dump.sql")
[ "$SIZE" -gt 1024 ] || fail "dump is only ${SIZE} bytes -- refusing to upload it"

gzip -9 "$TMP/dump.sql" || fail "gzip returned non-zero"

aws s3 cp "$TMP/dump.sql.gz" "s3://${BUCKET}/${KEY}" --region "$REGION" \
  || fail "upload to s3://${BUCKET}/${KEY} returned non-zero"

echo "backed up ${SIZE} bytes to s3://${BUCKET}/${KEY}"
logger -t freezehub-backup -p user.info "backup ok: ${KEY} (${SIZE} bytes uncompressed)"
