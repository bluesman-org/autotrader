# Backups

Two layers: a nightly local dump next to the app, and an offsite copy of every dump
into Google Drive.

## What is kept where

- Local: `~/backups/autotrader/autotrader-<stamp>.archive.gz` on the host running the
  MongoDB container, 30 days retained (`AUTOTRADER_BACKUP_KEEP_DAYS`).
- Offsite: every archive is additionally copied to `autotrader-backups/` inside a mounted
  Google Drive, 90 days retained (`AUTOTRADER_BACKUP_GDRIVE_KEEP_DAYS`).
- The `autotrader` database only, excluding the `candles` collection — candles are
  re-downloadable from Bitvavo and excluded by design.

## How it runs

`scripts/backup-mongo.sh` runs from cron (`15 3 * * *`): it writes the dump, prunes the
local copies, then — when `AUTOTRADER_BACKUP_GDRIVE_DIR` is set in `.env` or the
environment — remounts the Drive if needed, copies the archive and byte-verifies it
(`cmp`). An offsite failure exits non-zero so it is visible in
`~/backups/autotrader/backup.log`; the local dump is already on disk at that point.

## The Google Drive mount

The offsite directory is a FUSE mount from
[google-drive-ocamlfuse](https://github.com/astrada/google-drive-ocamlfuse):

- Install: `sudo add-apt-repository ppa:alessandro-strada/ppa`, `sudo apt-get update`,
  `sudo apt-get install google-drive-ocamlfuse`.
- Authorize once with an OAuth client (Desktop type) from a Google Cloud project with the
  Drive API enabled: `google-drive-ocamlfuse -id <client-id> -secret <client-secret>`.
  Keep the OAuth app's publishing status **In Production**, otherwise the token expires
  every 7 days. The tokens live in `~/.gdfuse/default/`.
- Mount: `google-drive-ocamlfuse ~/GoogleDrive`. Known quirk in the 0.8/0.9 series: the
  command never returns from a successful mount, so start it detached
  (`setsid ... </dev/null &`). The backup script does that itself when the mount is
  missing; a cron `@reboot` line keeps it mounted for interactive use.
- Unmount: `fusermount3 -u ~/GoogleDrive`. If Google ever revokes the grant, re-run the
  authorize command and remount; the nightly run fails loudly in the meantime.

## Restore

```bash
# pick an archive (local or from the mounted Drive) and restore it to a scratch database
cp ~/GoogleDrive/autotrader-backups/autotrader-<stamp>.archive.gz /tmp/restore.archive.gz

docker exec -i autotrader-mongo mongorestore --archive --gzip \
  --nsFrom 'autotrader.*' --nsTo 'autotrader_restore.*' \
  --uri "mongodb://root:<password>@localhost:27017/?authSource=admin" < /tmp/restore.archive.gz
```

Then verify the restored collections (`autotrader_restore`) against the source and drop
`autotrader_restore` when done. A full drill was run on 2026-10-13: the archive made the
round trip byte-identical and every collection count matched the dump.

Note: `bot_configurations` entries are encrypted with the `ENCRYPTION_MASTER_KEY` they were
saved with; a restore needs that key (Bitwarden Secrets Manager / deploy secrets), which is
deliberately not part of the dump.
