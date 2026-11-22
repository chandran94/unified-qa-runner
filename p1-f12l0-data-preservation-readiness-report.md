# P1-F12L.0 Data Preservation Readiness

## Decision

**BLOCKED — DATA/ROLLBACK SAFETY NOT VERIFIED**

The live application is healthy and uses an identified disposable storage root, but the requested readiness gate requires a consistent backup strategy and recoverable current build. Neither can be proven without stopping/quiescing the live process or having a supported snapshot mechanism.

## Evidence

- PID 23304 remained alive and owned `127.0.0.1:8081`.
- HTTP health remained 200.
- Storage root was found in the task-owned disposable temp area and is outside the workspace.
- `qa-runner.json` is the primary shared persistent store; it contained schema version 5 and three synthetic auth records. No personal record values were exposed.
- `.qa-organizations.json` was absent in the observed root; only its lock file existed.
- Attachments, target media, and runner-report directories were absent in the observed root at inspection time.
- No backup was created in this read-only phase.

## Backup assessment

**BACKUP READINESS: BLOCKED.** Copying `qa-runner.json`, organization files, attachments, imports, media, and reports while the server may write would not guarantee a consistent cross-store snapshot. The source provides per-file temp replacement and an organization prior-write backup, but no coordinated application snapshot/export or quiescence mechanism.

Required safe method: obtain an approved maintenance window, prevent writes, gracefully quiesce the task-owned application, copy the complete identified storage root including metadata and file attributes, validate file hashes/manifest, and only then proceed. Do not label a live copy as a verified backup.

## Build/recovery assessment

The running JVM uses Java 25 and `target/classes` with `com.example.unifiedqarunner.Main`; its start time predates the current candidate class timestamps. The candidate compiles and focused tests pass, but the current loaded build cannot be reconstructed as a versioned artifact/hash. Existing target JARs are not accepted as a verified rollback pair.

**ROLLBACK READINESS: BLOCKED.** A separate versioned candidate and a preserved current artifact must be identified before restart approval.

## Compatibility review

Source review found additive file-backed schema/version behavior and temp-file replacement. User-story schema version is 5; organization schema version is 1. No destructive migration was executed or authorized. Because the current runtime artifact is not identifiable and no migration rehearsal was performed, compatibility is **PARTIALLY VERIFIED**, not guaranteed.

## Browser readiness

Manual browser verification is safe only through an authorized user-controlled session. No shared authenticated browser session was available, so live authenticated verification is **BLOCKED**. No cookies, tokens, passwords, or browser data were extracted.

## Recommended next action

Do not restart. First obtain approval for a maintenance window/quiescence procedure, create and validate a complete storage snapshot, preserve a versioned current artifact, produce a separately hashed candidate, and then request explicit controlled-restart approval.

## P1-F12L.0A implementation update

Persistent root resolution, offline manifest backup/verification, isolated restore, and schema compatibility validation were added and verified only with synthetic temporary data. The backup service fails closed when any expected persistent writer is not registered. Because the current production writers are not yet all integrated with that barrier, live consistent backup remains **BLOCKED**. No live backup, migration, restore, or restart was performed.

## P1-F12L.0B integration update

The coordinator is now shared per data root and protects User Story, organization, Wiki, authentication, defect, team and role writes. The snapshot lock correctly blocks active writes. Remaining direct writers are listed in [p1-f12l0b-coordinated-backup-integration.md](./p1-f12l0b-coordinated-backup-integration.md); therefore the fail-closed **BLOCKED** status is retained.
