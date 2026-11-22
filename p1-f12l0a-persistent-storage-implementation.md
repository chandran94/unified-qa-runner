# P1-F12L.0A — Persistent Storage Implementation

## Status

**Persistent root resolution: IMPLEMENTED AND VERIFIED in isolated tests.**

`Main` now resolves the existing application root through `StorageRootResolver`. The explicit `qa.runner.dataDir` system property takes precedence over `QA_RUNNER_DATA_DIR`. An already populated discovered root is never silently replaced by a different configured root; startup fails with an actionable migration-required error. Existing roots are preserved for compatibility. New roots default to a stable `.unified-qa-runner` directory under the user home when no discovered root exists.

The resolver canonicalizes paths, rejects a symlinked data root, rejects filesystem roots, and handles Windows-equivalent canonical path representations safely. No live root was resolved, migrated, or modified by this implementation.

## Architecture map

- `qa-runner.json`: authoritative application/auth/user-story/project/test/defect data.
- `.qa-organizations.json`: versioned organization store.
- `.qa-attachments`: attachment files.
- `imports`: imported project files.
- `target/screenshots`, `target/videos`, `target/runner-reports`: generated artifacts.
- sessions and OTP challenges: in-memory and intentionally non-persistent.

## Files changed for this phase

- `src/main/java/com/example/unifiedqarunner/Main.java`
- `src/main/java/com/example/unifiedqarunner/StorageRootResolver.java`
- `src/main/java/com/example/unifiedqarunner/StorageWriteCoordinator.java`
- `src/main/java/com/example/unifiedqarunner/BackupService.java`
- `src/main/java/com/example/unifiedqarunner/ReleaseMetadata.java`
- `src/main/java/com/example/unifiedqarunner/RollbackCompatibilityValidator.java`
- `src/test/java/com/example/unifiedqarunner/PersistentStorageRecoveryTest.java`

## Limitations

The existing stores still write independently. The coordinator therefore fails closed unless all declared writers are registered; it is not yet integrated into every production writer. No live backup may be advertised as cross-store consistent until that integration and an approved quiescence procedure are completed.

Production readiness remains **NOT APPROVED**.
