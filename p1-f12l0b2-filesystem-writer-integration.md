# P1-F12L.0B.2 — Filesystem Writer Integration

## Scope and safety

This delta phase did not stop PID 23304, touch port 8081, read or write live storage, create a live backup, send email, or run external integrations. All tests used temporary isolated roots.

## Remaining-writer inventory

| Writer/path | Classification | Result |
|---|---|---|
| `RunnerServer.uploadUserStoryAttachment` publication and deletion | Authoritative persistent attachment state | Coordinated with the existing per-root barrier |
| `RunnerServer.deleteScreenshot` / bulk screenshot deletion | Recoverable derived artifact | Deletion coordinated; producer is external/test-owned and not a coordinated Java writer |
| `RunnerServer.deleteVideo` / bulk video deletion | Recoverable derived artifact | Deletion coordinated; producer is external/test-owned and not a coordinated Java writer |
| `ReportWriter.write` (`target/runner-reports`) | Durable derived recovery artifact | Coordinated for summary/index publication |
| Plugin-specific direct report/log writes | Derived artifact | Not all route through `ReportWriter`; complete producer coverage remains open |
| Temporary ZIP/tool downloads under `temp`/`target/uploads` | Disposable temporary | Excluded from authoritative backup scope |

## Changes

- Added `reports` to the coordinator’s required authoritative writer set.
- Wrapped attachment publication and cleanup in the existing `StorageWriteCoordinator`.
- Wrapped screenshot/video deletion in the same per-root coordinator; no second coordinator was introduced.
- Coordinated `ReportWriter` publication and derived the project root from the existing `target/runner-reports` layout.
- Moved `SharedPathConfig.save()` and `RemoteImportConfig.save()` read/merge logic inside their coordinated write critical sections, fixing a concurrent partial-root overwrite race.

## Verification

- Maven compile: **PASS**.
- Focused tests: **44 PASS, 0 FAIL, 0 ERROR, 1 SKIPPED**.
  - Shared JSON preservation: 4/4 PASS.
  - Persistent recovery: 6/6 PASS.
  - Organization: 8/8 PASS.
  - User Story persistence: 11/11 PASS.
  - Ownership: 2/2 PASS.
  - User Story HTTP security: 5/5 PASS.
  - Report writer: 1/1 PASS.
  - Screenshot/video streaming: 6 PASS, 1 SKIPPED (OS-restricted symlink capability).
- `git diff --check`: **PASS** (exit 0; only existing line-ending warnings were reported by Git).

## Remaining blocker

The external screenshot/video producers and several plugin-specific direct report/log writers do not expose a common coordinated publication callback. Therefore the coordinator cannot honestly claim that every authoritative/recovery artifact writer participates in a consistent cross-module snapshot. Backup consistency remains **BLOCKED**, not VERIFIED.

Production readiness remains **NOT APPROVED**.

## P1-F12L.0B.3 delta

The host-controlled `ArtifactPublicationService` now provides bounded staging and atomic publication. Robot output is staged before publication, and additional host-generated plugin text/XML/log outputs use the service. External-process producers not yet redirected remain the reason backup consistency is **BLOCKED**.
