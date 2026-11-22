# P1-F12C Isolated Browser E2E Verification

Date: 2026-11-15

## Scope and isolation

The browser suite used only the supported `RunnerServer(Path, 0, ...)` constructor, a unique temporary storage root, synthetic users, synthetic organizations/projects, in-process verification delivery, and headless Chrome. No live credentials, SMTP, external services, or port 8081 were used. The live application remained PID 23304 on `127.0.0.1:8081` throughout.

Test source: [UserStoryBrowserE2ETest.java](./src/test/java/com/example/unifiedqarunner/UserStoryBrowserE2ETest.java).

Exact command:

```powershell
$env:MAVEN_OPTS='-Xmx512m -XX:MaxMetaspaceSize=192m'
mvn -q '-Dtest=UserStoryBrowserE2ETest' test
```

Final execution result: **2 tests, 1 PASS, 1 FAIL, 0 ERROR, 0 SKIPPED**. The failing test deliberately accumulated application-level browser gaps and then failed with both findings below. Selenium emitted only the known Chrome 157/CDP 4.27 compatibility warning.

## Browser matrix

| Scenario | Result | Evidence |
|---|---|---|
| Synthetic owner login and organization context | PASS within isolated workflow | Owner reached the organization-scoped User Story module. |
| User Story create through browser | PASS within failing workflow | `Browser story F12C` appeared in the story list. |
| Story sprint creation | PASS within failing workflow | `Browser Sprint F12C` appeared and was assigned to the story. |
| Story comment add/edit/delete | PASS within failing workflow | Comment was created, edited, entered inline delete confirmation, and deleted. |
| Sprint Board initial render | FAIL | UI displayed `Unable to load sprint board: A project scope is required for this operation.` |
| Kanban drag/drop and persisted status | NOT VERIFIED | Board could not render; no status mutation or rollback claim is made. |
| Test Case modal opens | PASS | Modal opened through the normal application handler. |
| Test Case creation | FAIL | UI displayed `Failed to save test case: The runner could not complete this request.` Server-side diagnostic reported `requestedProjectId` was null. |
| Test Case/story linking | NOT VERIFIED | Creation failed before linking. Existing HTTP link coverage remains in [UserStoryHttpSecurityTest.java](./src/test/java/com/example/unifiedqarunner/UserStoryHttpSecurityTest.java). |
| Defect creation/cross-module visibility | NOT VERIFIED | No mutation was attempted after the preceding Test Case failure. Existing HTTP defect-link coverage remains in the focused security suite. |
| Separate-organization visibility | PASS | The synthetic outsider saw `Other Browser Project` and did not see `Browser E2E Project` or the owner story. |
| Story archive/restore | NOT VERIFIED | This run did not mutate archive state. Store-level archive/restore coverage exists, but browser evidence is absent. |
| Responsive layout/form validation | NOT VERIFIED | No separate viewport or validation run was performed. |

## Findings

### P1-F12C-001 — Sprint Board loses project scope on initial render

Expected: an authorized project-scoped user opening Sprint Board sees the board for the loaded project.

Actual: the browser route rendered an error requiring project scope. The final `renderUserStoryBoard` path reads the board project selector before the selector exists, resulting in an empty `projectId` on the first request. The backend correctly rejected the unscoped request; this is a frontend context propagation defect, not an authorization defect.

Security impact: no bypass observed. The backend fail-closed behavior was preserved.

Recommended fix: initialize the first board request from the already loaded authorized project, then preserve that value when rendering the selector. Add a focused browser regression test and a negative authorization test. No fix was applied in this phase.

### P1-F12C-002 — Test Case creation submits without project context

Expected: an authorized user can create a Test Case from the Test Case module.

Actual: the modal opened, but the save request failed. The isolated server emitted the sanitized diagnostic `Cannot invoke "String.isBlank()" because "requestedProjectId" is null`, and the UI showed the generic failure message. The modal's story/project context is not initialized for a newly opened Test Case, while the backend requires a project scope.

Security impact: no authorization weakening observed. The backend rejected the incomplete request rather than creating an unscoped record.

Recommended fix: derive the selected organization/project context from the authorized application state and include it in the Test Case create request; retain server-side project validation. Add browser and HTTP regression coverage. No fix was applied in this phase.

## Cleanup and live safety

- Temporary `qa-user-story-browser-*` roots remaining after execution: **0**.
- Browser instances and isolated servers were closed by test teardown.
- PID 23304 remained alive.
- `127.0.0.1:8081` remained owned by PID 23304.
- Live HTTP GET `/` remained **200**.
- No live data, configuration, credentials, SMTP, or external service was accessed.
- No production source was modified by this phase; only the focused test source and reports were added.

## Decision

P1-F12C is **PARTIALLY VERIFIED**. The isolated browser harness is operational and proves organization isolation plus substantial story/sprint/comment behavior, but the Sprint Board and Test Case workflows have reproducible P1 functional gaps. Defect-linking, browser archive/restore, Kanban persistence/rollback, responsive checks, and full two-account role matrix remain unverified.

No production fix was applied. A separately approved remediation phase is required. Production readiness remains **NOT APPROVED**.

Security statuses remain: H-005 **CLOSED**, D1-F-001 **OPEN**, D1-F-002 **OPEN**.
