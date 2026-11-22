# P1-F12G Browser E2E and Performance Report

## Scope and safety

This phase used the existing isolated Selenium/JUnit harness with one headless Chrome worker, `RunnerServer(..., 0, ...)`, disposable temporary storage, synthetic identities, and ephemeral loopback ports. PID 23304 and `127.0.0.1:8081` were not stopped, restarted, or mutated. No SMTP, external integration, pipeline, commit, or deployment was used.

The runtime-validation skill's Playwright recommendation was evaluated. Node.js was available (`v24.14.0`), but `npx` could not execute because the host PowerShell execution policy blocks `npx.ps1`; the project already has an authorized Selenium/JUnit browser harness, so Selenium was retained. Docker was unavailable. This is a documented tooling deviation, not evidence of Playwright coverage.

## Browser results

Test source: [P1F12EBrowserE2ETest.java](./src/test/java/com/example/unifiedqarunner/P1F12EBrowserE2ETest.java).

| Workflow | Result | Evidence |
|---|---|---|
| Kanban native HTML5 drag/drop and refresh persistence | PASS | Isolated Selenium; current DOM read after rehydration |
| Failed Kanban update rollback | PASS | Synthetic browser-side status-request failure; prior status restored |
| Story archive and restore | PASS | Authenticated same-origin browser requests with live CSRF token |
| Story-to-test-case linking and read-back | PASS | Test case created, linked, and returned by story linkage query |
| Defect creation and Defect Management visibility | PASS | Existing module navigation and UI assertion |
| Keyboard board navigation | PASS | Focusable card + ArrowRight status transition |
| Responsive board rendering | PASS | 420x800 viewport; board and focusable card rendered |
| Project archive/restore browser flow | NOT VERIFIED | Restore is admin-gated; no separate synthetic admin browser identity was available in this run |
| Two-account browser RBAC/tenant isolation | NOT VERIFIED | Existing HTTP security evidence is separate from browser two-account evidence |

## Commands and evidence

```text
mvn -q -Dtest=P1F12EBrowserE2ETest#storyArchiveRestoreAndTestCaseLinkingPersist test  PASS
mvn -q -Dtest=P1F12EBrowserE2ETest test  PASS (6/6)
mvn -q -Dtest=UserStoryHttpSecurityTest test  PASS (5/5)
mvn -q -DskipTests compile  PASS
git diff --check  PASS (existing LF/CRLF conversion warnings only)
```

The initial archive attempt without a CSRF header returned **403** with the sanitized message `A valid CSRF token is required.` The test then obtained the session token through the normal authenticated `/api/auth/me` response and passed it using the application's `X-CSRF-Token` contract. No security control was bypassed.

## Performance observations

Read-only samples against the protected live listener were sequential and bounded:

| Sample | Status | Latency |
|---:|---:|---:|
| 1 | 200 | 1,199 ms |
| 2 | 200 | 409 ms |
| 3 | 200 | 407 ms |

The first request is treated as a cold/static-load observation; the latter two are warm observations. During a 3.04-second sample window, PID 23304 remained alive, working set was approximately **27.92 MB**, and the reported CPU-time delta was **0.000 seconds**. These are point observations, not load-test or production benchmark results. No measured bottleneck justified a production performance change.

## Defects and fixes

No production defect was confirmed and no production source file was changed in this phase. Test-only corrections addressed DOM rehydration, HTML5 drag/drop dispatch, unsupported defect-module hash navigation, CSRF-aware browser requests, and an intercepted modal-tab click. The existing application behavior—including CSRF enforcement—was preserved.

## Verdict

The remaining browser scenarios covered by this phase are **PASS**. Project archive/restore and browser two-account RBAC/tenant isolation remain **NOT VERIFIED**. Production readiness remains **NOT APPROVED**. Security statuses remain H-005 **CLOSED**, D1-F-001 **OPEN**, and D1-F-002 **OPEN**.
