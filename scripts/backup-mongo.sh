#!/usr/bin/env bash
#
# Backup the autotrader MongoDB, excluding the candles collection (they are
# re-downloadable from Bitvavo). Writes a mongodump archive, prunes old ones and
# — when AUTOTRADER_BACKUP_GDRIVE_DIR is set — copies each archive offsite to a
# mounted Google Drive and keeps a longer offsite window there.
#
# Usage: scripts/backup-mongo.sh
# Cron:  15 3 * * * /home/cemk/projects/autotrader/scripts/backup-mongo.sh >> /home/cemk/backups/autotrader/backup.log 2>&1
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${AUTOTRADER_ENV_FILE:-$SCRIPT_DIR/../.env}"
BACKUP_DIR="${AUTOTRADER_BACKUP_DIR:-$HOME/backups/autotrader}"
KEEP_DAYS="${AUTOTRADER_BACKUP_KEEP_DAYS:-30}"
CONTAINER="${AUTOTRADER_MONGO_CONTAINER:-autotrader-mongo}"

# shellcheck disable=SC1090
source "$ENV_FILE"
mkdir -p "$BACKUP_DIR"

# Read after sourcing the env file so .env can opt the offsite copy in.
GDRIVE_DIR="${AUTOTRADER_BACKUP_GDRIVE_DIR:-}"
GDRIVE_SUBDIR="${AUTOTRADER_BACKUP_GDRIVE_SUBDIR:-autotrader-backups}"
GDRIVE_KEEP_DAYS="${AUTOTRADER_BACKUP_GDRIVE_KEEP_DAYS:-90}"

STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="$BACKUP_DIR/autotrader-$STAMP.archive.gz"

docker exec "$CONTAINER" mongodump \
  "mongodb://root:${MONGO_ROOT_PASSWORD}@localhost:27017/?authSource=admin" \
  --db autotrader --excludeCollection candles --archive --gzip > "$OUT.part"
# Rename only after the dump exits 0: a failed dump must not leave a partial
# archive under the final name the restore runbook picks by.
mv "$OUT.part" "$OUT"

echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) backup written: $OUT ($(du -h "$OUT" | cut -f1))"

find "$BACKUP_DIR" \( -name 'autotrader-*.archive.gz' -mtime +"$KEEP_DAYS" \
  -o -name 'autotrader-*.archive.gz.part' -mtime +0 \) -delete

# Offsite copy to a mounted Google Drive (google-drive-ocamlfuse). Opt-in: set
# AUTOTRADER_BACKUP_GDRIVE_DIR to the mountpoint. When set, every failure is
# loud — the local dump above is already on disk, so a silently missing offsite
# copy is the one thing this must never do. Every check and step that touches
# the mount is bounded by a timeout: a mount whose daemon is up but wedged makes
# even the stat() behind `mountpoint` hang, and without the bounds the cron run
# would hang instead of failing.
if [ -n "$GDRIVE_DIR" ]; then
  if ! timeout 60 mountpoint -q "$GDRIVE_DIR"; then
    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) offsite: $GDRIVE_DIR is not mounted, attempting to mount"
    # google-drive-ocamlfuse 0.8+ never returns from the mount; detach it and
    # keep the child's output so the reason for a failed mount survives.
    setsid google-drive-ocamlfuse "$GDRIVE_DIR" </dev/null >>"$BACKUP_DIR/gdrive-mount.log" 2>&1 &
    for _ in $(seq 1 15); do
      timeout 60 mountpoint -q "$GDRIVE_DIR" && break
      sleep 2
    done
  fi
  if ! timeout 60 mountpoint -q "$GDRIVE_DIR"; then
    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) offsite copy FAILED: $GDRIVE_DIR is not mounted (see gdrive-mount.log)" >&2
    exit 1
  fi
  DEST="$GDRIVE_DIR/$GDRIVE_SUBDIR"
  DEST_FILE="$DEST/$(basename "$OUT")"
  if ! timeout 5m mkdir -p "$DEST"; then
    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) offsite copy FAILED: could not reach $DEST" >&2
    exit 1
  fi
  # Stage under .part and rename only after the comparison: a truncated file
  # must never carry the final name the restore runbook picks archives by.
  if ! timeout 15m cp "$OUT" "$DEST_FILE.part"; then
    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) offsite copy FAILED: copy timed out or errored" >&2
    exit 1
  fi
  if timeout 5m cmp -s "$OUT" "$DEST_FILE.part"; then
    if ! timeout 5m mv "$DEST_FILE.part" "$DEST_FILE"; then
      echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) offsite copy FAILED: could not finalize $DEST_FILE" >&2
      exit 1
    fi
    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) offsite copy written: $DEST_FILE"
  else
    timeout 5m rm -f "$DEST_FILE.part" || true
    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) offsite copy FAILED: verification mismatch" >&2
    exit 1
  fi
  if ! timeout 5m find "$DEST" \
      \( -name 'autotrader-*.archive.gz' -mtime +"$GDRIVE_KEEP_DAYS" \
      -o -name 'autotrader-*.archive.gz.part' -mtime +0 \) -delete; then
    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) offsite prune FAILED: old copies not removed" >&2
    exit 1
  fi
fi
