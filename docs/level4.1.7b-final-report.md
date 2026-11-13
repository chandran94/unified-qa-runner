# Level 4.1.7B — Isolated Authenticated Browser Verification

**Generated:** 2026-11-13 22:40 Asia/Calcutta  
**Target:** Unified QA Runner

## 1. Connectivity root cause

- `Get-NetTCPConnection -LocalPort 8081 -State Listen` returned `NO_LISTENER_8081`.
- The shared browser page was `chrome-error://chromewebdata/` for `http://127.0.0.1:8081/`; the second shared page was `about:blank`.
- The browser/application network namespace could not be directly identified through the available browser controls. The observed `ERR_CONNECTION_REFUSED` is nevertheless fully explained by the missing listener; no process was restarted or terminated.

## 2. Isolated server evidence

The existing disposable live harness was inspected at `src/test/java/com/example/unifiedqarunner/LiveTwoAccountAuthorizationTest.java`. It constructs `RunnerServer(root, 0, delivery, delivery)`, uses a temporary root, binds the ephemeral server to loopback, keeps verification codes in the test process, and closes the server and browser sessions in teardown. No P0.14-named file exists; this is the available P0.13-style equivalent.

The live test completed with Maven exit code `0`. After completion, port 8081 still had no listener. The isolated ephemeral port was released by the test teardown; its exact port is not emitted by the test report.

## 3. Validation results

| Scope | Result | Evidence |
|---|---:|---|
| Maven compile | PASS | `mvn.cmd -q compile`, exit `0` |
| Organization API integration | PASS | 3 tests, 0 failures |
| Tenant route integration | PASS | 2 tests, 0 failures |
| Authenticated browser E2E | PASS for existing project-scoped harness | 1 Selenium/Chromium test, exit `0` |
| Level 3 selected regression | PASS | 56 tests, 0 failures/errors/skips, exit `0` |
| `git diff --check` | PASS | exit `0` (only LF/CRLF warnings) |
| Full focused selection including `RunnerServerTest` | FAIL | 23 tests, 3 failures, exit `1` |

## 4. Organization UI matrix

The requested organization-management browser flows were **not executed** because no organization browser spec exists in the available harness. The existing live browser test verifies project/team/wiki authorization, test-case creation, CSRF, logout, and cross-project denial, but not the organization selector, organization panel, settings, invitations, role updates, revocation, or organization-scoped UI modules.

| Journey | Browser evidence |
|---|---|
| Login/logout | PASS in existing live harness |
| Organization creation/list/selector/switch | UNVERIFIED |
| Organization management/settings | UNVERIFIED |
| Role update/revocation/pending invitation | UNVERIFIED in browser; API assertions pass where covered |
| Invitation acceptance | API integration PASS; browser UNVERIFIED |
| Organization-specific projects/test cases/wiki/team/user stories | API/route coverage partial; browser UNVERIFIED |

## 5. Tenant switching security

Source inspection confirms the frontend calls `AbortController.abort()` and increments a request generation on organization switch, clears the project selection, and removes unauthorized stored selections. Tenant API tests confirm server-side filtering and cross-tenant denial. There is no real browser test proving delayed A responses cannot overwrite B, no cross-tenant flash occurs, or that the browser UI immediately clears A data. Those claims remain UNVERIFIED.

## 6. Actual HTTP status evidence

Assertions in the executed integration/live tests provide these observed contract checks:

- `200`: authorized organization/project/wiki/team reads; logout; invitation acceptance.
- `201`: authorized test-case and organization-project creation.
- `401`: unauthenticated protected reads and post-logout `/api/auth/me`.
- `403`: cross-project/cross-tenant reads and writes; unauthorized organization management; missing CSRF.
- `409`: last-owner demotion protection.
- Invitation responses do not expose the raw token or token hash.

These are test assertions, not invented or manually substituted results.

## 7. Remaining blockers

1. Organization management and tenant-switching browser coverage is missing from the runnable browser harness.
2. The focused run including `RunnerServerTest` has three failures: legacy expectations receive `401` instead of expected `200`, `202`, and `400`.
3. Selenium emitted a CDP compatibility warning for Chromium 157 with Selenium 4.27.0; the browser test still passed.
4. `npx` was not used because PowerShell blocks `npm.ps1`; Node itself is available (`v24.14.0`). This does not justify dropping the existing Selenium browser tier.

## Final verdict

**Level 4.1.7B: BLOCKED.** The isolated authenticated server and existing project-scoped security browser test pass, but the requested organization-management browser proof and stale-request tenant-switching proof were not executed, and the focused suite has three failing legacy tests.

**Production readiness: NOT APPROVED.**

Level 4.1.8 and PostgreSQL were not started.
