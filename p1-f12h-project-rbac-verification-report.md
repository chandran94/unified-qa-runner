# P1-F12H Project Lifecycle and Two-Account RBAC Verification

Date: 2026-11-15

## Scope and safety

Verification used [P1F12HProjectRbacBrowserE2ETest.java](./src/test/java/com/example/unifiedqarunner/P1F12HProjectRbacBrowserE2ETest.java), one disposable storage root per test, one `RunnerServer(root, 0, ...)` instance, one headless Chrome worker, and an ephemeral loopback port. No live data, SMTP, external integrations, PID 23304, or port 8081 were used by the test server.

After execution PID 23304 remained `java.exe`, owned `127.0.0.1:8081`, and returned HTTP 200. No matching `qa-p1-f12h-browser-*` temporary roots remained after test cleanup.

## Results

| Scenario | Result | Evidence |
|---|---|---|
| Admin project creation | PASS | Authenticated browser request to `/api/organizations/{organizationId}/projects` returned 201. |
| Admin project archive | PASS | Authenticated same-origin request returned 200 and `archived=true`. |
| Admin project restore | PASS | Authenticated same-origin request returned 200 and `archived=false`. |
| Story persistence across project restore | PASS | Isolated `UserStoryStore` read-back retained the story identity. |
| Sprint persistence across project restore | PASS | Isolated `UserStoryStore` read-back retained the sprint identity. |
| Missing CSRF on archive | PASS | Member request without `X-CSRF-Token` returned 403. |
| Viewer archive denial | PASS | Member request with the valid session CSRF token returned 403. |
| Cross-organization project disclosure | PASS | Member’s Organization A-scoped project catalog did not contain the Organization B project. |
| Isolated archive latency | OBSERVED | 32 ms in this run; one sample, not a target or benchmark. |
| Isolated restore latency | OBSERVED | 27 ms in this run; one sample, not a target or benchmark. |

`mvn -q -Dtest=P1F12HProjectRbacBrowserE2ETest test`: **2/2 PASS, 0 FAIL, 0 ERROR, 0 SKIPPED**, exit code 0.

## Authorization interpretation

The Admin route is implemented as a platform/team `Admin` gate in [RunnerServer.java](./src/main/java/com/example/unifiedqarunner/RunnerServer.java), through `requireAuthenticatedAdmin`, rather than an organization-specific Admin gate. The second synthetic identity was a Viewer with membership only in the other project. It could not archive the project, and the Organization A-scoped project listing did not disclose the Organization B project. No production authorization defect was confirmed and no production source was changed.

The test did not fabricate Product Owner or Project Manager privileges. Their exact behavior remains **NOT VERIFIED** here. Project archive/restore was verified through authenticated browser requests; a complete visual click-path for project restore is **NOT VERIFIED** because the current frontend does not expose a dedicated project restore control. The supported source-level restore endpoint is present.

## Regression evidence

- `mvn -q -Dtest=UserStoryHttpSecurityTest test`: **5/5 PASS**, exit code 0.
- `mvn -q -Dtest=P1F12EBrowserE2ETest test`: **6/6 PASS**, exit code 0.
- `mvn -q -DskipTests compile`: **PASS**, exit code 0.
- `git diff --check`: no whitespace errors; only existing LF/CRLF conversion warnings.

## Cleanup and final assessment

The test-owned browser and server were closed by `@AfterEach`; disposable roots were deleted. No process was stopped. PID 23304 and port 8081 were not interrupted. No commit, push, deployment, or findings-register change occurred.

The isolated project lifecycle and Viewer authorization scenarios are **PASS**. Full two-account role matrix, organization-switching UI, stale-context behavior, Product Owner/Project Manager role-specific browser behavior, and visual project restore are **NOT VERIFIED**. Production readiness remains **NOT APPROVED**. H-005 remains **CLOSED**; D1-F-001 and D1-F-002 remain **OPEN**.
