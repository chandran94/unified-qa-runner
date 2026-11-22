# P1-F12L Controlled Restart Preflight

Generated: 2026-11-15

## Decision

**BLOCKED — restart approval must not be requested as ready.** The live process is healthy and isolated, but the current running build cannot be identified to a reproducible artifact/hash, no verified backup artifact was created in this read-only phase, and no authorized authenticated browser session was available for live UI verification.

No restart, stop, kill, live mutation, email, deployment, commit, or push was performed.

## Runtime preflight

| Check | Result | Evidence |
|---|---|---|
| PID 23304 alive | PASS | Java process present; start time 2026-11-15 16:32:09 |
| Port ownership | PASS | `127.0.0.1:8081`, owner PID 23304 |
| HTTP health | PASS | Three bounded GET requests returned HTTP 200 |
| Loopback binding | PASS | Listener is `127.0.0.1:8081` |
| Duplicate instance | NOT OBSERVED | No second listener on 8081 |
| Java runtime | PASS | Microsoft JDK 25.0.5.21 |
| Main class | SOURCE/PROCESS REVIEWED | `com.example.unifiedqarunner.Main` |
| Storage isolation | PASS | Disposable temp root exists outside the project; no overlap detected |
| Live build identity | UNKNOWN/STALE | Process loads `target/classes`; PID began before the current candidate class timestamps |
| Authenticated browser session | BLOCKED | No shared authorized browser session was available |

The sanitized launch shape observed for PID 23304 is Java 25 with bounded heap/metaspace, a Maven-resolved classpath ending in `target/classes`, main class `com.example.unifiedqarunner.Main`, the disposable Phase 3E storage root, and `--port=8081`. Secret-bearing values were not recorded.

## Candidate build verification

`mvn -q -DskipTests compile` completed with exit code 0 using:

```text
$env:MAVEN_OPTS='-Xmx512m -XX:MaxMetaspaceSize=192m'
mvn -q -DskipTests compile
```

Focused sequential verification completed with exit code 0:

```text
mvn -q -Dtest=ZipImportResourceLimitsTest,ScreenshotVideoStreamingTest,UserStoryHttpSecurityTest,P1F12IRbacOrganizationBrowserE2ETest test
```

Results: **21 PASS, 1 SKIPPED, 0 FAIL, 0 ERROR**. The symlink test was skipped because Windows did not permit disposable symlink creation. This validates the candidate classes in isolation; it does not prove PID 23304 is running them.

The Maven project is `unified-qa-runner:1.0-SNAPSHOT` with compiler, Surefire, and Shade plugins. Existing candidate JARs are not treated as a verified rollback artifact because their build identity and relationship to PID 23304 are not independently established.

## Data/configuration safety

The observed live storage root is a disposable task-owned path outside the workspace. No backup was created because this phase is read-only; therefore backup integrity is **NOT VERIFIED**, not claimed. The repository's `qa-runner.json` was not used as a backup or modified by this preflight.

## Read-only browser and performance results

Authenticated browser verification: **BLOCKED** because no shared authorized session was available. No live mutations or external integrations were attempted.

Measured live read-only samples:

- HTTP `/`: **200** at **608 ms**, **447 ms**, and **398 ms**; 3 samples.
- PID 23304 working set: approximately **25.06 MB** at the end of sampling.
- CPU-time delta during the bounded sample window: **0 ms** observed.
- No load or stress testing was performed.

## Security status

- H-005: **CLOSED**.
- D1-F-001: **OPEN**.
- D1-F-002: **OPEN**.
- Production readiness: **NOT APPROVED**.

## Required next step

Before any controlled restart, create and validate a non-overwriting backup of the disposable storage, identify or produce a versioned candidate artifact with a recorded hash, and obtain an authorized shared browser session for live read-only verification. Then request separate explicit restart approval.

## P1-F12L.0 preservation audit update

The source-level storage inventory found shared file-backed persistence in `qa-runner.json`, a separate versioned organization store, separate attachment/media/report paths, and in-memory sessions. Temp-file replacement reduces single-file truncation risk, but no coordinated quiescence or transactional snapshot exists across stores. Backup readiness and rollback readiness therefore remain **BLOCKED**. See [p1-f12l0-runtime-storage-inventory.md](./p1-f12l0-runtime-storage-inventory.md), [p1-f12l0-data-preservation-readiness-report.md](./p1-f12l0-data-preservation-readiness-report.md), and [p1-f12l0-safe-rollback-procedure.md](./p1-f12l0-safe-rollback-procedure.md).

## P1-F12L.0A implementation status

The candidate now has explicit persistent-root resolution, fail-closed snapshot coordination, offline verified backup/restore, release metadata, and rollback schema checks. Isolated recovery tests passed 5/5 and compile passed. This does not change the preflight decision: live backup consistency is **BLOCKED** until every persistent writer participates and a versioned current artifact is preserved.
