# P1-F12L.0A — Release and Rollback Readiness

## Release metadata

`ReleaseMetadata` computes an artifact SHA-256, deterministic hash-derived build identifier, application version, source revision, build timestamp, and Java feature compatibility. Hashing is streaming and does not load the artifact into memory.

## Compatibility

`RollbackCompatibilityValidator` rejects absent required application data, invalid JSON roots, and future user-story or organization schema versions. It does not downgrade data or mutate the active root.

## Verification

Release metadata and future-schema rejection are covered by `PersistentStorageRecoveryTest`; the focused test class passed 5/5. Maven compile passed. `git diff --check` exited 0 (only existing line-ending warnings were reported).

## Readiness

**ROLLBACK READINESS: BLOCKED.** The currently running JVM still uses mutable `target/classes` and its exact artifact hash cannot be proven. A complete consistent backup has not been created, and all production writers are not yet registered with the quiescence barrier. A separate versioned candidate artifact and preserved current artifact are required before any restart approval.

Required future maintenance procedure: identify and hash the current artifact, build/hash a versioned candidate, quiesce all writers, create and verify a non-overwriting backup, then obtain explicit restart approval. No restart was performed.

Production readiness remains **NOT APPROVED**.
