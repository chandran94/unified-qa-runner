# Level 4.1.8C-2C — H-005 Shared Artifact and Report Isolation

Batch C-2C: **PASS for the selected report-file family**  
H-005 overall: **OPEN**  
Production readiness: **NOT APPROVED**

## Audit and selection

The audited families were execution screenshots, execution videos, execution logs, test-run attachments, test-result artifacts, report files, report exports, download/preview paths, artifact metadata, and background-generated files. Source evidence and risk ranking are in [the artifact ownership matrix](./level4.1.8c2c-artifact-ownership-matrix.md).

The selected family was **report files served from completed runs**. The affected route is `GET /api/runs/{id}/report`, routed at [RunnerServer.java:736](../src/main/java/com/example/unifiedqarunner/RunnerServer.java:736) and implemented by [serveReport](../src/main/java/com/example/unifiedqarunner/RunnerServer.java:2352).

It ranked highest for this small batch because it exposes potentially sensitive HTML/CSV-derived execution output through a direct download path and had a concrete symlink containment gap. The route already has authoritative run/project authorization, making the filesystem fix independently testable. Screenshots/videos and physical shared namespace migration were deferred because their writers, attribution, listing, download, and deletion paths require a broader coordinated storage change.

## Root cause

Before this batch, `serveReport` used normalized lexical containment and `Files.isRegularFile`. A symlink beneath `target/runner-reports` could therefore resolve outside the approved root while still passing the lexical check. The report was also loaded entirely into memory with `Files.readAllBytes`.

## Modified files

- [ReportStorageSecurity.java](../src/main/java/com/example/unifiedqarunner/ReportStorageSecurity.java:8) — real-path trusted-root containment and regular-file enforcement.
- [RunnerServer.java](../src/main/java/com/example/unifiedqarunner/RunnerServer.java:2352) — report resolution through the security helper and streamed response.
- [ReportStorageSecurityTest.java](../src/test/java/com/example/unifiedqarunner/ReportStorageSecurityTest.java:13) — ordinary, outside-root, and symlink escape tests.

The uncommitted C-2B files were preserved and were not changed by this batch: [C-2B final report](./level4.1.8c2b-final-report.md), [RunnerServer.java](../src/main/java/com/example/unifiedqarunner/RunnerServer.java), [index.html](../src/main/resources/static/index.html), and [TenantRouteIntegrationTest.java](../src/test/java/com/example/unifiedqarunner/TenantRouteIntegrationTest.java).

## Ownership and filesystem enforcement

- The existing route gate still authorizes the run through `TEST_RUN` project ownership, active tenant membership, project membership, and `TEST_EXECUTION` permission before `serveReport` executes.
- Report files are accepted only when their real path is beneath the real trusted `target/runner-reports` root.
- Symlink escapes, outside-root paths, missing files, and non-regular files fail closed with the existing generic 404 behavior.
- The response is streamed from the validated path rather than loaded wholesale into memory.
- Existing restrictive content-security policy and `no-store` behavior are preserved.
- No client filename or report path is trusted as an ownership assertion.
- No legacy file is auto-assigned and no live storage migration was performed.

## Before/after behavior

| Case | Before | After |
|---|---|---|
| Authorized regular report | Served | Served, streamed |
| Missing report | Generic 404 | Generic 404 |
| Lexically inside symlink to outside | Could pass lexical check | Rejected by real-path containment |
| Outside-root report | Rejected lexically | Rejected by real-path containment |
| Large report | Entire file loaded into memory | Streamed with filesystem response |
| Cross-tenant run/report | Existing run ownership denial | Existing denial preserved |

## Test results

Focused selected-family tests:

- `ReportStorageSecurityTest`: **3/3 PASS**.
- `RunnerReportTest`: **1/1 PASS**.
- Tenant route suite: **8/8 PASS**.
- C-2A automation execution suite: **2/2 PASS**.

The combined targeted regression invocation completed **48/48 PASS**:

| Suite | Result |
|---|---:|
| ReportStorageSecurityTest | 3/3 |
| RunnerReportTest | 1/1 |
| TenantRouteIntegrationTest | 8/8 |
| AutomationExecutionManagerTest | 2/2 |
| TenantJobContextTest | 4/4 |
| TenantContextResolverTest | 6/6 |
| OrganizationApiIntegrationTest | 3/3 |
| DatabaseConnectionManagerTest | 3/3 |
| TeamAuthorizationTest | 6/6 |
| RunnerServerTest | 3/3 |
| AuthenticationFoundationTest | 4/4 |
| LocalTwoAccountSecurityHarnessTest | 3/3 |
| ProjectOwnershipManagerTest | 2/2 |

Maven compile passed and `git diff --check` passed. No PostgreSQL, Docker, cloud deployment, real customer execution, Selenium suite, or port 8081 restart was performed.

## Preserved controls

C-001, C-002, H-001, H-002, H-003, H-004, C-2A, and C-2B remained covered by the targeted regression set. No changes were made to screenshot/video bulk deletion or CI/CD result-import authorization in this batch.

## Deferred and remaining H-005 gaps

- Screenshots and videos: shared physical namespaces, symlink policy, and full direct/list/delete matrix.
- Execution logs: in-memory ownership and retention/restart behavior.
- Attachments: full symlink, retention, and cross-project matrix.
- Report exports: no independent export endpoint was found.
- Background artifact writers: namespace collisions and attribution race behavior.
- Broader shared execution/integration stores, runner registration, distributed claims, PostgreSQL, and cloud deployment.

H-005 remains **OPEN** because only one report-file family was independently remediated and verified.

## Final disposition

- Batch C-2C: **PASS** for the selected report-file family.
- H-005 overall: **OPEN**.
- Production readiness: **NOT APPROVED**.
- Stop condition met; no C-2D, C-2C-3, PostgreSQL, or cloud work was started.
