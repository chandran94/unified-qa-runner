# P1-F12K.2 Independent Security Review

## Scope and safety

This independent review examined the current source and isolated focused-test evidence for D1-F-001 and D1-F-002. The live Unified QA Runner process (PID 23304) and `127.0.0.1:8081` were not stopped, restarted, probed for mutations, or otherwise modified. No live data, credentials, SMTP delivery, external integration, commit, push, or deployment was used.

The review is independent of earlier PASS summaries: source paths, alternate callers, test behavior, and closure criteria were checked before classification.

## Code paths reviewed

### ZIP import

The reviewed path is:

`POST /api/imports/zip` → `RunnerServer.importFromZip` → `RemoteImportConfig.extractZipFile`.

The extraction path uses `ZipInputStream` and applies archive-size, entry-count, per-entry decompressed-byte, aggregate decompressed-byte, path-depth, duplicate-path, ZIP-Slip, nested-archive, and cleanup controls. Actual bytes written are counted while extraction proceeds. The separate private/tool-installer ZIP extraction path in `RunnerServer.extractZip` is not the user remote-import path and should not be treated as D1-F-001 coverage without a separate scope decision.

### Screenshot/video streaming

The reviewed paths are `/screenshots/{file}` and `/videos/{file}` through `serveScreenshot`/`serveVideo`, authorization and artifact-ownership checks, `resolveContainedMediaFile`, and `streamFileResponse`.

The media handlers now resolve both the trusted root and candidate with `toRealPath()`, require the resolved candidate to remain below the resolved root, and require a regular file. Streaming remains bounded by the existing 16 KiB buffer and try-with-resources lifecycle. Authentication, ownership, CSRF, and tenant checks were not weakened.

## D1-F-001 independent assessment

### Findings

- `ZipImportLimits` has a positive, finite configurable ratio with a default of `100.0`.
- Archive bytes are obtained from the exact ZIP file size via `Files.size(zipFile)`.
- Decompressed bytes are counted from actual bytes written, not only ZIP metadata.
- `checkedAdd` protects per-entry and aggregate counters from signed-long overflow.
- A non-empty output against a zero-byte archive is rejected by the ratio guard; an empty archive is rejected by the existing no-entry requirement.
- Multiple entries are covered by the aggregate output limit and archive-level ratio; each entry also has its own decompressed-byte limit.
- Rejection cleanup remains in the existing extraction cleanup path.
- Invalid and non-positive ratio values are rejected or safely defaulted according to the current configuration logic.

### Important scope distinction

The current ratio check is an exact **archive-level** ratio:

`actual decompressed bytes written / exact ZIP file bytes`.

It is not an exact **per-entry compressed-payload** ratio. `ZipInputStream` does not, by itself, provide independently trustworthy compressed byte accounting for every entry across headers, read-ahead, data descriptors, ZIP64 forms, and malformed archives. Therefore the current implementation is a meaningful conservative archive expansion bound, but it does not independently prove a requirement that specifically demands per-entry compressed-to-uncompressed ratios.

### Evidence

`ZipImportResourceLimitsTest` passed 6/6, including a small high-compression synthetic archive rejected by the archive-level ratio guard. No large archive or unbounded decompression was used.

### Classification

**D1-F-001: OPEN — residual closure-scope gap.** The implemented archive-level guard is verified, but closure cannot be granted until the original finding scope is confirmed to accept archive-level accounting or a separately approved maintained parser provides trustworthy per-entry compressed-payload accounting. No finding register was changed.

## D1-F-002 independent assessment

### Findings

- Authorized normal screenshot/video download behavior remains covered by focused tests.
- Traversal, missing-file, zero-byte, suspended-user, and unauthorized access cases remain covered by isolated tests.
- The real-path helper rejects lexical escapes, broken links, and resolved targets outside the trusted root before streaming.
- The stream uses a bounded buffer and try-with-resources, so ordinary completion and I/O failure paths request closure according to the HTTP server contract.
- A subsequent download after a client-disconnect scenario succeeds in the focused test, which is evidence against a permanently stuck shared resource.

### Remaining verification limits

- Windows disposable symlink creation was unavailable without elevated privileges or security-setting changes. The screenshot and video symlink escape case was therefore skipped, not counted as PASS.
- Direct observation of the server-side input/output close sequence after a client disconnect is not exposed by the current server contract. Successful subsequent access is useful but does not prove every close event.
- A filesystem race in which a link is replaced between real-path resolution and opening is not independently stress-tested in this phase.

### Evidence

`ScreenshotVideoStreamingTest`: 6 PASS, 1 SKIPPED, 0 FAIL, 0 ERROR. The skip is the Windows symlink capability condition. `UserStoryHttpSecurityTest`: 5/5 PASS. The organization/RBAC focused suite: 2/2 PASS.

### Classification

**D1-F-002: OPEN — verification gaps remain.** Real-path containment is implemented and source-reviewed, but runtime symlink-escape evidence and direct disconnect cleanup observability do not satisfy the requested closure gate.

## Focused verification record

| Scope | Result |
|---|---:|
| `ZipImportResourceLimitsTest` | 6 PASS |
| `ScreenshotVideoStreamingTest` | 6 PASS, 1 SKIPPED |
| `UserStoryHttpSecurityTest` | 5 PASS |
| `P1F12IRbacOrganizationBrowserE2ETest` | 2 PASS |
| Combined focused batch | 19 PASS, 1 SKIPPED, 0 FAIL, 0 ERROR |
| Maven compile | PASS, exit 0 |
| `git diff --check` | PASS, exit 0; existing line-ending warnings only |

Executed focused command:

```text
mvn -q -Dtest=ZipImportResourceLimitsTest,ScreenshotVideoStreamingTest,UserStoryHttpSecurityTest,P1F12IRbacOrganizationBrowserE2ETest test
```

No full Maven suite, live-server mutation, fixed-port test server, external integration, or browser worker was used for this review.

## Runtime safety and release decision

- PID 23304 remained present and was not stopped or restarted.
- `127.0.0.1:8081` remained the live listener and returned HTTP 200 in the final safety check.
- Focused disposable media and ZIP roots were cleaned; no matching temporary roots remained.
- H-005 remains **CLOSED**.
- D1-F-001 remains **OPEN** pending confirmation of per-entry versus archive-level ratio scope.
- D1-F-002 remains **OPEN** pending executable symlink evidence and stronger disconnect-cleanup observability.
- Production readiness remains **NOT APPROVED**.

## Recommended next action

Obtain an independent scope decision for D1-F-001. For D1-F-002, use an already approved non-Windows symlink-capable disposable test environment or add a narrowly scoped test-only observability seam through the normal review process. Neither finding should be closed from the current evidence alone.

## P1-F12K.3 final focused verification

Two additional small adversarial ZIP tests were added to [ZipImportResourceLimitsTest.java](./src/test/java/com/example/unifiedqarunner/ZipImportResourceLimitsTest.java): multiple highly compressible entries, a deflated data-descriptor entry, and an empty archive rejection. These tests validate actual output accounting and cleanup without attempting to infer per-entry compressed payload from untrusted metadata.

The final sequential batch exited **0**: ZIP **8 PASS**, media **6 PASS and 1 SKIPPED**, User Story HTTP security **5 PASS**, and organization/RBAC **2 PASS**. Combined: **21 PASS, 1 SKIPPED, 0 FAIL, 0 ERROR**. Compile exited 0 and `git diff --check` exited 0.

The first run of the new tests exited 1 because the 1 KiB fixture threshold was too permissive; correcting the test threshold from 10:1 to 5:1 produced the passing bounded rerun. This was a test-fixture defect, not a production failure. Final classifications remain D1-F-001 **OPEN** and D1-F-002 **OPEN**. H-005 remains **CLOSED** and production readiness remains **NOT APPROVED**.
