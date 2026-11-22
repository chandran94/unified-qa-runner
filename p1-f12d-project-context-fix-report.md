# P1-F12D Project Context Fix Report

Date: 2026-11-15
Scope: Sprint Board and Test Case project-context initialization
Status: FIXED AND ISOLATED-TESTED; live build not restarted

## Baseline and root cause

P1-F12C executed two isolated browser tests: one passed and the owner workflow failed. The failures were frontend context-propagation defects. The backend fail-closed checks were working as designed.

1. Sprint Board initialization read the project selector before the selector was populated, producing an empty project scope. After the first scope correction, the request still sent `sprintId=` for “All sprints”; the backend correctly treats an omitted sprint filter as unfiltered, but an empty value filters out assigned stories.
2. Test Case creation did not include the active authorized `projectId`, so the server rejected the incomplete request. After that was corrected, the auxiliary aggregate-stats request returned the expected project-ownership 403 for the scoped role and the UI incorrectly treated that optional request as fatal, preventing the already-authorized Test Case list from rendering.

## Minimal changes

Production change: [src/main/resources/static/index.html](./src/main/resources/static/index.html)

- Added authorized project-context initialization from the current organization-scoped project list.
- Validated the selected project against the authorized project set and displayed a clear empty-context state.
- Omitted `sprintId` when “All sprints” is selected.
- Added the authorized project ID to Test Case creation and blocked submission when no project is available.
- Made optional Test Case aggregate statistics non-fatal and derived fallback counts from the authorized list.

Test change: [src/test/java/com/example/unifiedqarunner/UserStoryBrowserE2ETest.java](./src/test/java/com/example/unifiedqarunner/UserStoryBrowserE2ETest.java)

- Hardened the existing Selenium row-button helper against table re-render stale elements.
- Reacquired the Sprint Board tab after refresh before asserting persisted status.

No backend authorization, tenant, CSRF, persistence, storage, or API contract changes were made. Existing server-side `requireProjectAccess`, project ownership assignment, and security checks remain authoritative.

## Verification

Commands, run sequentially with bounded Maven memory:

- `mvn -q -Dtest=UserStoryBrowserE2ETest#ownerCompletesStorySprintBoardCommentsAndTestCaseWorkflow test` — **1 PASS, 0 FAIL, 0 ERROR, 0 SKIPPED**.
- `mvn -q -Dtest=UserStoryBrowserE2ETest test` — **2 PASS, 0 FAIL, 0 ERROR, 0 SKIPPED**.
- `mvn -q -Dtest=UserStoryHttpSecurityTest test` — **5 PASS, 0 FAIL, 0 ERROR, 0 SKIPPED**.
- `mvn -q -DskipTests compile` — **PASS**.
- `git diff --check` — **PASS**; only pre-existing line-ending warnings were reported.

The isolated tests used temporary `qa-user-story-browser-*` storage, synthetic identities and data, a supported `RunnerServer(root, 0, ...)` loopback server, one headless browser worker, and deterministic teardown. No matching temporary roots remained after execution. No live data, SMTP, external service, or port 8081 was accessed.

## Results and limitations

- Project-scoped Sprint Board loading and persisted status: **PASS**.
- Authorized Test Case creation with project context: **PASS**.
- Organization isolation regression: **PASS**.
- Focused User Story HTTP security regression: **5/5 PASS**.
- The running PID 23304 still contains the prior loaded build; it does not contain these source changes because no live restart was authorized.
- Full Maven suite, live browser verification, browser defect linking, archive/restore, responsive checks, and the complete two-account live authorization matrix were not run in this phase.

Security statuses remain unchanged: H-005 **CLOSED**, D1-F-001 **OPEN**, D1-F-002 **OPEN**. Production readiness remains **NOT APPROVED**. A separately authorized controlled restart is required before these fixes can be exercised by the live application.
