# P1-F12K Security Remediation, Tenant Isolation and Data Protection

## Scope and safety

This review used source inspection and isolated disposable tests only. PID 23304 and `127.0.0.1:8081` were not stopped, restarted, or used for mutations. No live data, credentials, SMTP delivery, external integration, findings register, commit, push, or deployment was changed.

## Finding inventory

### D1-F-001 — OPEN

D1-F-001 affects the user ZIP import path: `POST /api/imports/zip` → `RunnerServer.importFromZip` → `RemoteImportConfig.extractZipFile`. Existing controls enforce archive size, entry count, per-entry decompressed bytes, total decompressed bytes, path depth, duplicate-path rejection, ZIP-Slip containment, non-recursive nested archives, and cleanup after failed extraction. These controls are covered by [ZipImportResourceLimitsTest.java](./src/test/java/com/example/unifiedqarunner/ZipImportResourceLimitsTest.java), 5/5 passed.

The unresolved requirement is exact compressed-to-decompressed ratio enforcement. The current `ZipInputStream` flow does not expose independently trustworthy per-entry compressed payload accounting. A hand-written parser or unapproved dependency would be a higher-risk change. No speculative ratio control was added. D1-F-001 remains OPEN pending separately approved maintained-parser evaluation or explicit risk acceptance.

### D1-F-002 — OPEN

D1-F-002 affects screenshot/video delivery. The existing handlers use a fixed 16 KiB transfer buffer, authorization before streaming, content-length responses, and try-with-resources cleanup. The remaining concrete source gap was lexical-only containment: a symlink below the media root could pass `Files.isRegularFile` and then be followed outside the trusted root.

## Remediation applied

The minimal production change is in [RunnerServer.java](./src/main/java/com/example/unifiedqarunner/RunnerServer.java):

- `serveScreenshot` and `serveVideo` now resolve through `resolveContainedMediaFile`.
- The helper resolves the trusted media root and candidate with `toRealPath()`.
- The resolved candidate must remain below the resolved trusted root and be a regular file.
- Missing files, broken links, and escaped symlink targets fail closed without streaming bytes.
- Existing authentication, ownership checks, content types, content length, and bounded streaming remain unchanged.

The focused regression was extended in [ScreenshotVideoStreamingTest.java](./src/test/java/com/example/unifiedqarunner/ScreenshotVideoStreamingTest.java) to cover both screenshot and video symlink escapes when Windows permits disposable symlink creation.

## Security verification results

| Area | Result | Evidence |
|---|---|---|
| Screenshot/video authorized integrity and headers | PASS | `ScreenshotVideoStreamingTest`, 6 passing cases |
| Suspended-user denial and no media disclosure | PASS | Focused media test |
| Zero-byte media behavior | PASS | Focused media test |
| Subsequent download after client disconnect | PASS / limited | Subsequent request succeeds; direct server handle observation is unavailable |
| Symlink escape runtime test | SKIPPED | Windows symlink capability unavailable; source now fails closed by real path |
| ZIP size/count/path/cleanup limits | PASS | `ZipImportResourceLimitsTest`, 5/5 |
| User Story HTTP authorization | PASS | `UserStoryHttpSecurityTest`, 5/5 |
| Organization/RBAC isolation | PASS | `P1F12IRbacOrganizationBrowserE2ETest`, 2/2 |
| Cross-tenant and archived-object access | SOURCE/TEST REVIEWED | Covered by prior isolated reports; no live probing performed |

## Commands and artifacts

- `mvn -q -Dtest=ScreenshotVideoStreamingTest,ZipImportResourceLimitsTest,UserStoryHttpSecurityTest,P1F12IRbacOrganizationBrowserE2ETest test` — exit 0.
- Results: 6 media passes, 1 media skip, 5 ZIP passes, 5 HTTP-security passes, 2 RBAC passes; 18 executed passes, 1 skipped, 0 failures, 0 errors.
- `mvn -q -DskipTests compile` — exit 0.
- `git diff --check` — exit 0; existing line-ending warnings only.
- No test server or browser resource was left running by the focused batch.

## Closure and release decision

D1-F-002 has a minimal real-path remediation, but it is not closure-eligible yet because runtime symlink execution was unavailable and direct server-side disconnect cleanup remains only partially observable. D1-F-001 remains open because exact compression-ratio enforcement is unresolved. No finding was closed.

- H-005: **CLOSED**, unchanged.
- D1-F-001: **OPEN**, unchanged.
- D1-F-002: **OPEN**, unchanged.
- Production readiness: **NOT APPROVED**.

## P1-F12K.1 closure update

The ZIP import now enforces a configurable archive-level compression ratio using exact archive file bytes from `Files.size(zipFile)` and actual decompressed bytes counted while writing output. The existing per-entry and total decompressed-byte limits remain authoritative. The default ratio is 100:1 and can be configured with `qa.runner.zip.maxCompressionRatio`; invalid values fail back to the safe default and non-positive/non-finite limits are rejected.

The new high-compression synthetic archive regression passed. Updated results:

- `ZipImportResourceLimitsTest`: **6/6 PASS**.
- `ScreenshotVideoStreamingTest`: **6 PASS, 1 SKIPPED**, 0 failures/errors.
- `UserStoryHttpSecurityTest`: **5/5 PASS**.
- `P1F12IRbacOrganizationBrowserE2ETest`: **2/2 PASS**.
- Combined focused execution: **19 PASS, 1 SKIPPED, 0 FAIL, 0 ERROR**.
- Compile: **PASS**; `git diff --check`: **PASS**.

D1-F-001 now satisfies the implemented ZIP-bomb closure gate for archive ratio, size, count, path, nested-archive, and cleanup behavior in isolated tests. D1-F-002 remains OPEN because Windows did not permit executable symlink creation and direct disconnect resource cleanup is not directly observable through the current server contract. No findings register was changed.

## P1-F12K.2 independent closure review

The independent review confirmed that the ZIP ratio guard is an exact archive-level expansion bound using `Files.size(zipFile)` and actual decompressed bytes written. It does not establish exact per-entry compressed-payload accounting with the current `ZipInputStream` path. D1-F-001 remains **OPEN — residual closure-scope gap** pending confirmation that archive-level accounting satisfies the original requirement or approval of a maintained parser approach.

Media real-path containment is source-reviewed and fail-closed for screenshot/video escapes while preserving authentication, ownership, CSRF, bounded streaming and try-with-resources cleanup. Focused results remain **6 media PASS, 1 Windows symlink SKIPPED**, **6 ZIP PASS**, **5 User Story HTTP-security PASS**, and **2 organization/RBAC PASS**: **19 PASS, 1 SKIPPED, 0 FAIL, 0 ERROR**. D1-F-002 remains **OPEN** because runtime symlink execution was unavailable and direct disconnect close events are not directly observable. PID 23304 and port 8081 remained untouched; compile and `git diff --check` passed. Full details: [p1-f12k2-independent-security-review.md](./p1-f12k2-independent-security-review.md).

## P1-F12K.3 final focused verification

Added small multi-entry, data-descriptor, and empty-archive ZIP regressions. The final batch exited 0 with **21 PASS, 1 SKIPPED, 0 FAIL, 0 ERROR**: ZIP **8/8**, media **6 PASS/1 SKIPPED**, User Story HTTP security **5/5**, and organization/RBAC **2/2**. Compile and `git diff --check` passed. The Windows symlink capability and direct disconnect observability gaps remain; D1-F-001 and D1-F-002 remain OPEN.
