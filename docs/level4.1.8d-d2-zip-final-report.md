# Level 4.1.8D D2 ZIP Final Report

## Outcome

D1-F-001 was narrowly remediated for the implemented user ZIP import path. The batch is **PARTIALLY REMEDIATED — NOT ELIGIBLE FOR UNCONDITIONAL CLOSURE** because exact compression-ratio enforcement remains unavailable with the current parser path and was not falsely claimed.

## Root cause and affected path

`POST /api/imports/zip` in `RunnerServer.importFromZip` writes the uploaded ZIP to a temporary project-root upload file and calls `RemoteImportConfig.extractZipFile`. Before this batch, extraction streamed files but had no actual decompressed entry/aggregate limits, entry-count bound, path-depth bound, duplicate collision policy, or failed-extraction cleanup. Existing ZIP-Slip normalization was preserved.

The separate private `RunnerServer.extractZip` tool-installer path was not modified because it processes trusted tool downloads rather than the confirmed user ZIP import boundary; it remains a separate D1/D4 review item if its trust assumptions change.

## Policy and controls

- Archive input: 50 MiB default, with early known-length rejection and bounded existing request read.
- Entries: 1,024 maximum, including directories.
- Per-entry decompressed bytes: 8 MiB.
- Total decompressed bytes/temporary content budget: 64 MiB.
- Path depth: 32 components.
- Nested archives: retained, never recursively unpacked.
- Duplicate normalized paths: rejected.
- Partial extraction: deleted only under the UUID-scoped trusted imports root.
- Compression ratio: not exactly enforced; limitation explicitly recorded.

## Files changed for this batch

- Production: `src/main/java/com/example/unifiedqarunner/RemoteImportConfig.java`, `src/main/java/com/example/unifiedqarunner/RunnerServer.java`.
- Focused test: `src/test/java/com/example/unifiedqarunner/ZipImportResourceLimitsTest.java`.
- Reports: the seven `level4.1.8d-d2-zip-*.md` files in `docs/`.

The worktree already contained unrelated prior changes; those were preserved and not counted as D2 changes.

## Validation

- Focused synthetic ZIP tests: 5/5 PASS.
- ZIP plus remote-import tenant regression: 7/7 PASS.
- Maven compile: PASS via `mvn -DskipTests compile`.
- `git diff --check`: PASS at report generation time.
- No live application restart; port 8081 untouched.
- No external import, Docker, PostgreSQL, full Maven suite, or commit/push.

## Security preservation

No authentication, RBAC, CSRF, tenant/project ownership, JSON persistence schema, H-005 register, screenshot/video handling, or live data behavior was intentionally changed. Existing valid small imports remain functional in the focused test. No real application data was modified.

## Remaining status

- D1-F-001: **NOT ELIGIBLE — VERIFICATION REQUIRED / SEPARATE APPROVAL FOR EXACT RATIO CONTROL**.
- D1-F-002: OPEN/UNREMEDIATED and out of scope.
- H-005: CLOSED, unchanged.
- D3-D5: NOT STARTED.
- Production readiness: NOT APPROVED.
