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
  --db autotrader --excludeCollection candles --archive --gzip > "$OUT"

echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) backup written: $OUT ($(du -h "$OUT" | cut -f1))"

find "$BACKUP_DIR" -name 'autotrader-*.archive.gz' -mtime +"$KEEP_DAYS" -delete

# Offsite copy to a mounted Google Drive (google-drive-ocamlfuse). Opt-in: set
# AUTOTRADER_BACKUP_GDRIVE_DIR to the mountpoint. When set, every failure is
# loud — the local dump above is already on disk, so a silently missing offsite
# copy is the one thing this must never do.
if [ -n "$GDRIVE_DIR" ]; then
  if ! mountpoint -q "$GDRIVE_DIR"; then
    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) offsite: $GDRIVE_DIR is not mounted, attempting to mount"
    # google-drive-ocamlfuse 0.8+ never returns from the mount; detach it.
    setsid google-drive-ocamlfuse "$GDRIVE_DIR" </dev/null >/dev/null 2>&1 &
    for _ in $(seq 1 15); do
      mountpoint -q "$GDRIVE_DIR" && break
      sleep 2
    done
  fi
  if ! mountpoint -q "$GDRIVE_DIR"; then
    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) offsite copy FAILED: $GDRIVE_DIR is not mounted" >&2
    exit 1
  fi
  DEST="$GDRIVE_DIR/$GDRIVE_SUBDIR"
  mkdir -p "$DEST"
  cp "$OUT" "$DEST/"
  if cmp -s "$OUT" "$DEST/$(basename "$OUT")"; then
    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) offsite copy written: $DEST/$(basename "$OUT")"
  else
    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) offsite copy FAILED: verification mismatch" >&2
    exit 1
  fi
  find "$DEST" -name 'autotrader-*.archive.gz' -mtime +"$GDRIVE_KEEP_DAYS" -delete
fi
