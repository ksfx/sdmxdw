#!/usr/bin/env bash
# Nightly PostgreSQL backup for the sdmxdw database. Keeps the last 14 daily
# dumps; run via sdmxdw-backup.timer (see sdmxdw-backup.service).
set -euo pipefail

BACKUP_DIR="/var/backups/sdmxdw"
RETENTION_DAYS=14
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"

mkdir -p "$BACKUP_DIR"
PGPASSWORD="${SDMXDW_DB_PASSWORD}" pg_dump \
    -h localhost -U sdmxdw -d sdmxdw --format=custom \
    -f "$BACKUP_DIR/sdmxdw-$TIMESTAMP.dump"

find "$BACKUP_DIR" -name 'sdmxdw-*.dump' -mtime "+$RETENTION_DAYS" -delete

# Restore with:
#   pg_restore -h localhost -U sdmxdw -d sdmxdw --clean <dumpfile>
