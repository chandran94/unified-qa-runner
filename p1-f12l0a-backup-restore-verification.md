# P1-F12L.0A — Backup and Restore Verification

## Implementation

`BackupService` provides offline-only, non-overwriting backup and restore operations. Backups are staged, contain a format/versioned manifest, file count, total bytes and streaming SHA-256 hashes, and receive a completion marker only after verification. Existing verified destinations are never overwritten. Temporary/cache paths and lock/temp files are excluded.

Restore first verifies the completion marker, manifest totals, paths, file sizes and hashes. It creates a new target only, rejects unsafe paths/symbolic links, validates supported storage schema versions, and removes only the newly created target on failure. There is no public restore endpoint and no startup auto-restore.

## Evidence

`PersistentStorageRecoveryTest`: **6 PASS, 0 FAIL, 0 ERROR, 0 SKIPPED**.

Covered: explicit root precedence and populated-root protection; multiple JSON/attachment/import files; fail-closed writer registration; active-write timeout; nested writes; hash corruption rejection; future-schema rejection; isolated restore; preservation of a completed backup marker after a failed restore.

## Consistency classification

**PARTIALLY VERIFIED / LIVE BACKUP BLOCKED.** Existing JSON and file writers do not all participate in the new coordinator. `StorageWriteCoordinator` refuses a snapshot when any expected writer is missing, so the implementation cannot falsely claim a consistent live backup. A maintenance-window quiescence procedure and writer integration are still required before backing up the current runtime root.

No backup was run against live data. PID 23304 and port 8081 were not touched.

Production readiness remains **NOT APPROVED**.
