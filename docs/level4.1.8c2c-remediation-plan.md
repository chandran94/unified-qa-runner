# Level 4.1.8C-2C — Selected Report-File Remediation Plan

## Scope

Selected family: report files exposed by `GET /api/runs/{id}/report`.  
Deferred: screenshots, videos, logs, attachments, exports, physical artifact namespace migration, and all unrelated H-005 families.

## Root cause

`RunnerServer.serveReport` checked a normalized path with `startsWith` and `Files.isRegularFile`. The containment check was lexical, so a symlink inside the report tree could resolve outside the trusted root. The method also loaded the entire report into a byte array before writing it.

## Minimal fix

1. Retain the existing route-level `TEST_RUN` ownership authorization.
2. Resolve the trusted report root and candidate report with `Path.toRealPath()`.
3. Require the candidate real path to remain under the trusted report-root real path and be a regular file.
4. Return the existing generic 404 response for missing, invalid, or escaped reports without exposing filesystem paths.
5. Stream the file through a bounded filesystem stream rather than `readAllBytes`.
6. Preserve `Cache-Control: no-store` and the existing restrictive content-security policy.

## Verification plan

- Accept an ordinary report inside the trusted root.
- Reject a report outside the root.
- Reject a symlink resolving outside the root.
- Preserve existing tenant route behavior and run ownership checks.
- Run C-2A, C-2B, H-004, C-001/C-002, H-001/H-002/H-003, and Level 3 targeted regressions.

## Legacy and storage behavior

No legacy report is auto-assigned. Existing ownership-index rules remain authoritative. Files without a valid run/project ownership path remain inaccessible through the protected report route. No live migration or physical namespace rewrite is performed.

## Regression risks

The change may reject previously served symlinked or missing reports, intentionally failing closed. Normal reports generated beneath `target/runner-reports` retain their behavior. Streaming changes memory behavior but preserves response content and security headers.
