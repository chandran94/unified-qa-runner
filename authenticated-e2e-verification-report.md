# Unified QA Runner — P1-F11 Authenticated E2E Verification

## Status

**BLOCKED at authenticated access gate.** This report records only read-only preflight evidence. No credentials, cookies, tokens, or CSRF values were accessed or recorded.

## Runtime preflight

- Target process: PID 23304, Java, command line identifies `com.example.unifiedqarunner.Main`.
- Listener: `127.0.0.1:8081`, owned by PID 23304.
- Root health: HTTP 200.
- Duplicate application on port 8081: none observed.
- Runtime action: no restart, stop, second server, or configuration change.

## Browser status

The shared browser was opened at `http://127.0.0.1:8081/` and displayed the login form. User-assisted login is required before authenticated checks can begin. Codex did not enter or request credentials.

| Area | Result | Evidence |
|---|---|---|
| Login page rendering | PASS | Authentication controls rendered. |
| Authenticated session | BLOCKED | Manual login not completed. |
| Settings and Organization | BLOCKED | Authentication prerequisite unavailable. |
| User Story Management | BLOCKED | No authenticated read-only context. |
| Sprint/Kanban | BLOCKED | No authenticated read-only context. |
| Test Case Management | BLOCKED | No authenticated read-only context. |
| Defect Management | BLOCKED | No authenticated read-only context. |
| Tenant/RBAC/CSRF verification | BLOCKED | Requires authorized identities and authenticated requests. |
| Disposable mutations | NOT STARTED | Requires explicit approval of a disposable test-data plan. |

## Next safe action

Complete login manually in the shared browser, then report that it is ready for read-only verification. Before any create/edit/delete operation, an exact disposable test-data plan must be presented and explicitly approved.

Production readiness remains **NOT APPROVED**. H-005 remains CLOSED; D1-F-001 and D1-F-002 remain OPEN.

## Authenticated read-only results

Account context observed without recording identity details: signed-in user label was visible, but the application reported **No active organization** and the Organization selector contained **No authorized organizations**.

| Workflow | Result | Evidence |
|---|---|---|
| Dashboard | PASS | Dashboard rendered with signed-in user indicator, sidebar, and sign-out control. |
| Settings navigation | PASS | Settings route opened and displayed Organization management context. |
| Organization details | BLOCKED | No authorized organization membership was available; selector disabled. |
| Test Case Management | PASS (read-only shell) | Test Cases route rendered empty state, filters, pagination controls, and no test cases. |
| User Story Management | PARTIALLY VERIFIED | Route and tabs rendered, but loading reported project scope required. |
| Sprint Board | BLOCKED | Board view reported project scope required. |
| Defect Management | NOT VERIFIED | No accessible navigation/context was available for a safe read-only review. |
| Tenant/RBAC mutation checks | BLOCKED | No second authorized identity or project scope; no mutations attempted. |
| Navigation | PARTIALLY VERIFIED | Dashboard, Settings, Test Cases, User Stories, Sprint Board, Runner, Wiki, and Database routes were reachable. |

Observed runtime messages:

- `No active organization` and `No authorized organization` were displayed.
- User Story and Sprint Board reported `A project scope is required for this operation.`
- Database overview reported `This resource has no resolved project ownership.`
- Several 403 responses were observed during scoped module loading; without an authorized organization/project context, they are classified as **BLOCKED**, not as confirmed authorization defects.
- No create, edit, delete, run, import, pipeline, or external integration action was performed.

## Mutation gate

No mutation testing was performed. Before any disposable mutation test, approval is required for a plan containing a temporary organization/project, synthetic story and linked records, cleanup method, and confirmation that the account has authorized project scope.

## P1-F12A organization/project preflight

- Runtime preflight: **PASS** — PID 23304 owns loopback port 8081 and `/` returned HTTP 200.
- Authenticated session: **PASS** — existing user session remained active; no credentials or session data were recorded.
- Organization authorization: **NOT VERIFIED** — UI reports no active organization, no authorized organizations, and no active membership. The `+ Organization` control is enabled, but it was not clicked.
- Project authorization: **NOT VERIFIED** — no active organization/project scope was available.
- Existing data inspection: **READ-ONLY** — no organization or project records were changed.
- Cleanup capability: source review shows project archive/restore endpoints, but no organization delete endpoint was confirmed.

Relevant source evidence: organization creation requires authenticated CSRF and an authenticated user; User Story project creation requires same-origin and authenticated-admin authorization. These checks were reviewed but not exercised through mutation.

### Proposed mutation plan awaiting approval

Use a unique organization name `QA_E2E_F12_<run-id>` and project key/name `QA_E2E_PROJECT_F12_<run-id>`, create only through the existing UI/API, verify refresh and relationship, then archive the project. Because no organization-delete route was confirmed, the organization would remain as a clearly labeled disposable residual unless separate cleanup approval is provided. No mutation has started.

See [organization-cleanup-capability-audit.md](./organization-cleanup-capability-audit.md). Conclusion: organization deletion is unsupported; project archive/restore is source-supported; current-store mutations are not safe to approve without accepting residual organization data or using isolated storage.

The proposed isolated environment and launch gate are documented in [p1-f12a2-isolation-audit.md](./p1-f12a2-isolation-audit.md).

## P1-F12C isolated browser gap closure

Added [UserStoryBrowserE2ETest.java](./src/test/java/com/example/unifiedqarunner/UserStoryBrowserE2ETest.java) using a disposable root, ephemeral loopback port, synthetic identities, and one headless browser worker. Final result: **2 tests, 1 PASS, 1 FAIL, 0 ERROR, 0 SKIPPED**. The passing test verified separate-organization visibility. The failing workflow verified story creation, sprint creation/assignment, and comment add/edit/delete, then recorded Sprint Board missing project scope and Test Case creation missing project context. Kanban persistence/rollback, browser archive/restore, browser defect integration, responsive checks, and the full two-account RBAC matrix remain **NOT VERIFIED**. Details: [p1-f12c-browser-e2e-report.md](./p1-f12c-browser-e2e-report.md).

## P1-F12B isolated integration execution

The authorized isolated suite completed with **34 PASS, 0 FAIL, 0 ERROR, 1 SKIPPED** across 35 unique tests. Selenium organization tests and isolated HTTP/store tests used temporary roots and ephemeral loopback ports. `LiveTwoAccountAuthorizationTest` was skipped because `qa.runner.liveBrowser` was not set; it was not converted into a pass.

Temporary roots were cleaned successfully. PID 23304 remained alive, port 8081 remained owned by it, and live HTTP health remained 200. See [p1-f12b-isolated-integration-report.md](./p1-f12b-isolated-integration-report.md) for the complete matrix and limitations.

## P1-F12D isolated project-context verification

The previously failing Sprint Board and Test Case project-context paths were corrected in the static frontend and verified with the isolated browser suite. The full [UserStoryBrowserE2ETest.java](./src/test/java/com/example/unifiedqarunner/UserStoryBrowserE2ETest.java) class passed **2/2**; [UserStoryHttpSecurityTest.java](./src/test/java/com/example/unifiedqarunner/UserStoryHttpSecurityTest.java) passed **5/5**. These are isolated results, not live-browser evidence. PID 23304 and port 8081 remained untouched, and the live build was not restarted. Remaining live authenticated workflows are still not verified.

## P1-F12E browser integration

The visible User Story modal's Defects entry point passed an isolated browser check. Native Kanban drag/drop did not complete deterministic post-refresh rehydration within the bounded wait and is **NOT VERIFIED**, not classified as a product failure. Defect mutation/linking, archive/restore, rollback, responsive, keyboard, and complete browser two-account scenarios remain unverified. Details: [p1-f12e-browser-integration-report.md](./p1-f12e-browser-integration-report.md).

## P1-F12F browser gap closure

The corrected isolated browser harness passed Kanban HTML5 drag/drop with refresh persistence, Defects-tab availability, and defect creation with Defect Management visibility (**3/3 PASS**). The failures were test-only synchronization/navigation issues; no production source change was made. Rollback, archive/restore, browser Test Case bidirectional linking, responsive/keyboard behavior, and browser two-account RBAC remain **NOT VERIFIED**. See [p1-f12f-browser-gap-closure-report.md](./p1-f12f-browser-gap-closure-report.md).

## P1-F12G final browser/performance verification

The isolated Selenium class passed **6/6**, covering Kanban persistence, rollback, keyboard/mobile board behavior, story archive/restore, story-to-test-case linking, and defect visibility. The focused User Story HTTP security regression passed **5/5**. Project archive/restore and browser two-account RBAC remain **NOT VERIFIED**. Live PID 23304 and port 8081 remained untouched. Performance observations and tooling limitations are recorded in [p1-f12g-browser-performance-fix-report.md](./p1-f12g-browser-performance-fix-report.md).

## P1-F12H project lifecycle and two-account verification

The isolated [P1F12HProjectRbacBrowserE2ETest.java](./src/test/java/com/example/unifiedqarunner/P1F12HProjectRbacBrowserE2ETest.java) passed **2/2**, covering Admin project create/archive/restore, story/sprint preservation, CSRF rejection, Viewer archive denial, and cross-organization project non-disclosure. Archive/restore observations were 32 ms and 27 ms in one run, not performance targets. Full role-specific browser coverage, organization switching, stale-context behavior, and visual project restore remain **NOT VERIFIED**. See [p1-f12h-project-rbac-verification-report.md](./p1-f12h-project-rbac-verification-report.md).

## P1-F12I RBAC and organization switching audit

## P1-F12J project restore UI and RBAC

The static frontend now loads organization-scoped projects including archived records, defaults to Active, and provides Admin-only styled Archive/Restore actions. The isolated [P1F12JProjectRestoreBrowserE2ETest.java](./src/test/java/com/example/unifiedqarunner/P1F12JProjectRestoreBrowserE2ETest.java) passed **2/2**, including archive, archived discovery, restore, refresh persistence, Viewer denial, and source-derived Project Manager/Product Owner permission checks. PID 23304 was not restarted, so live verification of this new UI remains **NOT VERIFIED**. The prior P1-F12E harness synchronization issue was corrected test-only and now passes **6/6**. Production readiness remains **NOT APPROVED**.

The isolated [P1F12IRbacOrganizationBrowserE2ETest.java](./src/test/java/com/example/unifiedqarunner/P1F12IRbacOrganizationBrowserE2ETest.java) passed **2/2**. It verified the source-derived Admin, Project Manager, Product Owner, and Viewer permission snapshots; Viewer status mutation denial; two-organization project isolation; project-context reset to an authorized new-tenant project; and suppression of the prior tenant’s sprint ID in board requests. The backend Admin project restore API remains available, but the frontend has no dedicated archived-project discovery/restore control, so visual project restore is **NOT VERIFIED / CAPABILITY GAP**. See [p1-f12i-rbac-organization-security-audit.md](./p1-f12i-rbac-organization-security-audit.md).
