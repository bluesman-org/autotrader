#!/usr/bin/env bash
#
# Backup the autotrader MongoDB, excluding the candles collection (they are
# re-downloadable from Bitvavo). Writes a mongodump archive and prunes old ones.
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

STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="$BACKUP_DIR/autotrader-$STAMP.archive.gz"

docker exec "$CONTAINER" mongodump \
  "mongodb://root:${MONGO_ROOT_PASSWORD}@localhost:27017/?authSource=admin" \
  --db autotrader --excludeCollection candles --archive --gzip > "$OUT"

echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) backup written: $OUT ($(du -h "$OUT" | cut -f1))"

find "$BACKUP_DIR" -name 'autotrader-*.archive.gz' -mtime +"$KEEP_DAYS" -delete
