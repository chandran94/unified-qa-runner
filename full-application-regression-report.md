# Unified QA Runner — Full Application Regression Investigation

Date: 2026-11-15
Branch: `master`
HEAD: `4c273b99b237546bcec0b98506e47b39dd3400ec`

## Executive result

No new reproducible P1/P0 regression was identified in the bounded isolated regression run. The latest source compiled successfully and the selected focused suite completed with **135 PASS, 0 FAIL, 0 ERROR, and 1 SKIPPED** test. The skipped case is the Windows symlink-capability test in `ScreenshotVideoStreamingTest`; it was skipped because the required symlink capability was unavailable, not because of an observed product failure.

Full live/browser regression remains incomplete. PID 1264 was not present when checked and no confirmed listener was available on port 8081. The live build therefore could not be compared with the current source, and no live browser or HTTP verification was performed. Production readiness remains **NOT APPROVED**.

## Safety and provenance baseline

Existing worktree changes were preserved. The worktree already contained accumulated source, test, documentation, launcher, and isolated-data changes before this investigation. This task did not attribute those changes to a phase and did not modify or remove them.

No application was started, stopped, restarted, or contacted. Port 8081 was untouched. No live storage or customer data was accessed. No commit, push, deployment, or destructive command was performed.

Current status check showed the pre-existing changes listed below, plus this report:

- Tracked source/test changes in authentication, `RunnerServer`, `DefectManager`, organization/browser tests, and static UI.
- Untracked prior-phase reports, launcher files, isolated JSON artifacts, password-reset code/tests, and user-story security tests.
- No production or test source change was made by this P1 investigation.

## Evidence executed

| Check | Command/result | Evidence |
|---|---|---|
| Compile | `mvn -q -DskipTests compile` — exit 0 | Latest source compiled; one Maven process, bounded heap (`-Xmx512m`, `MaxMetaspaceSize=192m`). |
| Focused isolated regression suite | Explicit selected `-Dtest=...` Maven command — exit 0 | 136 test cases: 135 PASS, 0 FAIL, 0 ERROR, 1 SKIPPED. |
| Current two-account authorization browser test | `mvn -q -Dtest=LiveTwoAccountAuthorizationTest -Dqa.runner.liveBrowser=true test` — exit 0 | 1/1 PASS using an isolated ephemeral server/browser, not port 8081. |
| JavaScript syntax | Existing Phase 4A validation evidence — PASS | No current frontend source change was made in this task. |
| Whitespace | `git diff --check` — PASS | Only normal line-ending warnings were reported; no whitespace errors. |
| Live process | Read-only PID/socket inspection | PID 1264 not found; no confirmed 8081 listener. |

The isolated suite included User Story persistence/security, organization/browser, authentication recovery, authorization, tenant routing, reports, wiki, CI/CD/pipeline, local runner, media streaming, ZIP limits, and related focused classes. It did not constitute a full Maven suite.

## Module audit

| Module | Previously working behavior/evidence | Current observed behavior | Classification | Root cause / blocker |
|---|---|---|---|---|
| Dashboard | `RunnerServerTest` passed; dashboard/static navigation exists in `src/main/resources/static/index.html`. | No live browser rendering was possible. | PARTIALLY VERIFIED | Live process absent; browser UI evidence incomplete. |
| User Story Management | `UserStoryStoreTest` 11/11 and `UserStoryHttpSecurityTest` 5/5 passed, covering CRUD-related persistence, comments, sprint mapping, attachments, links, tenant/RBAC behavior, and XSS sanitization. | Full browser CRUD, filters, pagination, and refresh workflow were not executed in this task. | PARTIALLY VERIFIED | Browser harness/data setup for the complete UI flow is not available in the current evidence. |
| Sprint/Kanban Board | Store and HTTP tests passed, including lifecycle assignment, status persistence, and cross-tenant move denial. | Browser drag/drop, rollback, keyboard navigation, and filter behavior were not run. | PARTIALLY VERIFIED | Live/browser verification blocked. |
| Test Case Management | Tenant-route, runner, link, and authorization regressions passed. | Full browser CRUD/link workflow was not run. | PARTIALLY VERIFIED | No live browser evidence. |
| Defect Management | Defect persistence/link security tests passed; `DefectManager` was included in the accumulated worktree changes. | Complete browser create/edit/link/unlink flow was not run. | PARTIALLY VERIFIED | No live browser evidence. |
| Test Execution | `AutomationExecutionManagerTest`, pipeline/status, runner, and report-focused tests passed. | End-to-end browser execution flow and external execution integrations were not tested. | PARTIALLY VERIFIED | External integrations intentionally excluded. |
| CI/CD | Tenant-route and pipeline status tests passed. | No external GitHub/CI execution was attempted. | PARTIALLY VERIFIED | External integration intentionally excluded. |
| Reports | `RunnerReportTest` and report-storage security coverage passed. | Browser report viewing/export was not run. | PARTIALLY VERIFIED | No live browser evidence. |
| Team Management | `TeamAuthorizationTest` 6/6 and team/member/role tests passed; Level 4.1.7D coverage passed in the selected suite. | Complete live browser team workflow was not run. | PARTIALLY VERIFIED | Runtime/browser proof remains incomplete. |
| Organization Management | `OrganizationStoreTest`, `OrganizationApiIntegrationTest` 3/3, and `OrganizationBrowserE2ETest` 4/4 passed in isolated environments. | Covered organization UI scenarios pass, but no live instance was available for comparison. | VERIFIED for covered isolated scenarios; otherwise PARTIALLY VERIFIED | Scope is limited to tested scenarios. |
| Wiki/Documentation | `WikiManagerTest` and tenant/security-related tests passed. | Full browser authoring/navigation flow was not run. | PARTIALLY VERIFIED | No live browser evidence. |
| Local Runner | `RunnerServerTest` 3/3 and execution tests passed. | Full live local-runner UI behavior was not run. | PARTIALLY VERIFIED | Live process absent. |
| Settings | Organization navigation/relocation was covered by isolated organization browser tests. | Other settings screens were not independently browser-tested. | PARTIALLY VERIFIED | Scope limited to covered organization navigation. |
| QA Studio | No clear implemented `QA Studio` module was found during bounded source search. | No feature or test evidence was available. | NOT FOUND / NOT VERIFIED | Requires product-scope confirmation before treating as a regression. |

## Regression and root-cause assessment

### Confirmed findings

No new functional regression was reproduced in the selected isolated suite. The previously observed Project Manager authorization failure was not reproduced: `LiveTwoAccountAuthorizationTest` passed 1/1 after its isolated organization/project fixture and scoped-request correction. That result supports a prior fixture/scope mismatch rather than a current confirmed RBAC regression, but it does not prove all authorization paths.

The only non-pass result was the Windows symlink test, which was capability-gated and skipped. It is not evidence of a product failure; a privileged/Developer-Mode symlink environment would be required for that scenario.

### Current verification blocker

The live application could not be inspected because PID 1264 was absent and no confirmed port 8081 listener was found. Consequently, the following could not be established:

- whether the running build contains the latest source changes;
- live JavaScript console errors or failed API requests;
- live persistence behavior;
- live browser behavior across all listed modules.

This is classified as a **build/version and runtime-availability verification blocker**, not as evidence that the modules are broken.

### Fixes applied in this investigation

None. No reproducible defect required a safe code change within this task. No production source, test source, configuration, dependency, or persistence file was modified by this investigation.

## Security status

The selected isolated tests covered authentication recovery, CSRF-related protected routes, RBAC, tenant routing, cross-tenant HTTP security, project ownership, organization management, media authorization, ZIP limits, and User Story links/attachments. These results provide focused regression evidence, not a complete production security certification.

Existing security statuses remain unchanged:

- H-005: **CLOSED**.
- D1-F-001: **OPEN**.
- D1-F-002: **OPEN**.
- Production readiness: **NOT APPROVED**.

## Remaining work and safe next action

The next approved step should be a separate isolated browser regression phase using a disposable server, ephemeral port, and disposable storage. It should cover the module workflows marked PARTIALLY VERIFIED, especially User Story CRUD/integrations, Kanban UX and rollback, comments, attachments, Test Case/Defect links, Settings, Reports, Wiki, and Local Runner. No live restart should be attempted without explicit authorization and confirmed process/storage ownership.

## Validation and restrictions

- `git diff --check`: PASS.
- No Maven full suite was run.
- No live application, browser, HTTP port 8081, external integration, Docker, or database service was started or contacted.
- No credentials, OTPs, tokens, private paths, or customer data were included.
- No commit or push was performed.

## Phase P1-F4 controlled-startup preflight

Preflight was read-only and did not start the application.

- Branch: `master`; HEAD: `4c273b99b237546bcec0b98506e47b39dd3400ec`.
- Java process inspection found only the VS Code Java language-server JVM; no Unified QA Runner process was identified.
- No process owned port 8081 and no 8081 listener was found.
- The supported entry point is `com.example.unifiedqarunner.Main`.
- The latest compiled `target/classes/com/example/unifiedqarunner/Main.class` exists. The runtime classpath file `target/runtime-classpath.txt` exists.
- The reviewed launcher identifies the disposable storage root as `C:\Users\elayachandran\AppData\Local\Temp\vscode-agent-codex-sandbox-EfA3Uy\qa-runner-phase3e-32733901852845dfa3d62916084bc0e1`, and its `qa-runner.json` exists. This path is classified as task-owned temporary storage by the launcher; it is not the repository root.
- The launcher refuses to start when port 8081 is occupied, does not terminate existing processes, uses headless Java, and writes task-owned stdout/stderr logs beneath the disposable storage root.
- The application’s compatibility store is `qa-runner.json`; no database migration or reset is part of the prepared startup.

### Startup authorization gate

The exact reviewed startup procedure is:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\scripts\phase3l-gmail-smtp-launcher.ps1
```

The launcher then prompts privately for the business Gmail sender and masked App Password, starts the latest compiled classes with `com.example.unifiedqarunner.Main`, the disposable storage root, `--port=8081`, loopback-only mode, and bounded JVM memory. It must not be executed until the user explicitly authorizes live local startup.

Backup/rollback: no live data will be overwritten by this preflight. Before any approved startup, preserve the existing disposable storage directory as-is; if the task-owned process fails, stop only that task-owned process after confirming its PID and leave the storage directory intact. Do not use the repository-root `qa-runner.json` as a substitute.

Status: **STARTUP NOT EXECUTED — PRIVATE SMTP CREDENTIAL ENTRY REQUIRED**.

Phase P1-F5 authorization was received, but the reviewed launcher requires the business Gmail address and masked App Password to be entered in a trusted local PowerShell prompt. Those secrets cannot be requested, handled, or transmitted through this chat. The exact launcher command is documented in [phase3l-gmail-smtp-launcher-usage.md](docs/security/phase3l-gmail-smtp-launcher-usage.md).

Verified pre-start backup: `C:\Users\elayachandran\AppData\Local\Temp\qa-runner-phase4f5-backup-20261115-160512` (2,721 files; 161,296,939 bytes; `qa-runner.json` SHA-256 matches the source). An earlier empty task-created backup directory was not used; the verified backup is the path above.

## Phase P1-F5B post-startup verification

The manually attempted launcher startup was verified read-only and is not healthy:

- Unified QA Runner JVM: **not found**.
- Port 8081: **no listener**.
- HTTP health probe to `http://127.0.0.1:8081/`: **failed to connect** within the bounded timeout.
- Startup output contained the normal availability line, but no running process remained for verification. The available task-owned logs did not establish a durable application lifecycle or a complete startup exception.
- No restart, retry, termination, browser launch, live-data access, or port intervention was performed by this task.

Therefore all requested live browser smoke scenarios are **NOT VERIFIED**. No regression can be attributed to a module from this attempt; the immediate blocker is application process/runtime availability. A separately approved single startup diagnosis is required before browser testing can continue.

## Phase P1-F6 bounded startup diagnosis

Static lifecycle review found that `Main.main` calls `server.start()`, prints the URL, and then blocks in `server.awaitTermination()`. `RunnerServer` uses a `CountDownLatch`, so a normal successful start should remain alive; daemon worker threads alone do not explain a normal exit.

The reviewed launcher creates a child JVM with `Start-Process` and redirects stdout/stderr to task-owned logs. Its available logs contain the normal availability line but no captured startup exception. The visible stderr entries are later request-handling messages involving a null `requestedProjectId`; they do not establish the original startup failure.

The one authorized diagnostic invocation was rejected by the terminal safety layer before process creation. No Java process was created, no exit code was obtained, and no retry was attempted. The exact failure category remains **INSUFFICIENT EVIDENCE**. No fix was applied.

## Phase P1-F8 immediate-exit root cause

The task-owned `phase3l-startup.err.log` now contains a definitive startup exception:

```text
Exception in thread "main" java.lang.IllegalStateException: SMTP verification delivery is incompletely configured.
    at com.example.unifiedqarunner.SmtpVerificationDelivery.fromEnvironment(SmtpVerificationDelivery.java:44)
    at com.example.unifiedqarunner.AuthVerificationManager.configuredEmailDelivery(AuthVerificationManager.java:126)
    at com.example.unifiedqarunner.AuthVerificationManager.<init>(AuthVerificationManager.java:36)
    at com.example.unifiedqarunner.RunnerServer.<init>(RunnerServer.java:110)
    at com.example.unifiedqarunner.Main.main(Main.java:36)
```

This is an **application startup configuration failure**. `RunnerServer` is constructed before `server.start()` and before port binding. `SmtpVerificationDelivery.fromEnvironment()` treats any partial SMTP configuration as selected and fails closed when SMTP host, username, or password is blank. The launcher hardcodes the host/transport settings and obtains sender/username/password interactively; the evidence does not reveal which individual secret-bearing value was blank, and no secret was exposed.

The process therefore exits with an uncaught main-thread exception. The launcher reports the child PID but does not wait for it or capture its exit code, so the numeric exit code is **not available**. There is no evidence of a port-binding failure, JVM resource exhaustion, normal daemon-thread exit, or SMTP network authentication attempt. The failure occurs before SMTP connection and before HTTP health is possible.

Recommended minimal corrective action: rerun the reviewed launcher once after privately confirming that the sender is valid and the App Password prompt receives a non-empty Google App Password. Do not put credentials in arguments, files, logs, or chat. A launcher-side non-empty masked-password validation could improve diagnostics, but that is a separate code-change approval and was not implemented here. No automatic retry was made.

## Phase P1-F7 supported manual-start gate

Read-only preflight passed:

- No Unified QA Runner process is active.
- Port 8081 is free.
- `com.example.unifiedqarunner.Main`, `target/classes/com/example/unifiedqarunner/Main.class`, and `target/runtime-classpath.txt` exist.
- Disposable storage contains `qa-runner.json`.
- The verified backup at `C:\Users\elayachandran\AppData\Local\Temp\qa-runner-phase4f5-backup-20261115-160512` exists and its `qa-runner.json` SHA-256 matches the disposable source.
- SMTP secrets were not read or displayed. The launcher requires private interactive input for SMTP sender/App Password.

The exact reviewed command for the user’s trusted VS Code PowerShell terminal is:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\scripts\phase3l-gmail-smtp-launcher.ps1
```

This command was **provided but not executed by Codex**. The user must enter any prompted SMTP credentials privately into the masked launcher prompt. After execution, report the launcher PID or startup result without sharing credentials, OTPs, or tokens.

## Phase P1-F9 SMTP configuration fix

The launcher and Java variable names were verified to match: `QA_RUNNER_SMTP_HOST`, `QA_RUNNER_SMTP_PORT`, `QA_RUNNER_SMTP_USERNAME`, `QA_RUNNER_SMTP_PASSWORD`, `QA_RUNNER_SMTP_FROM`, `QA_RUNNER_SMTP_STARTTLS`, and `QA_RUNNER_SMTP_SSL`. Java requires host, username, and password to be non-blank; the sender defaults to username when `QA_RUNNER_SMTP_FROM` is blank. The specific secret-bearing value that was blank cannot be identified from sanitized evidence.

Minimal fix applied:

- [phase3l-gmail-smtp-launcher.ps1](scripts/phase3l-gmail-smtp-launcher.ps1) now rejects a blank masked App Password before setting child-process SMTP variables or starting Java.
- Java SMTP validation remains fail-closed and unchanged.
- No credentials are logged, persisted, or placed in process arguments.

Focused verification: `mvn -q -DskipTests compile` exit 0; `mvn -q -Dtest=SmtpVerificationDeliveryTest test` exit 0; 6 PASS, 0 FAIL, 0 ERROR, 0 SKIPPED. Another manual startup is required to verify the fix in the JVM and obtain HTTP health evidence; it was not performed automatically.

## Phase P1-F10 read-only browser verification

Verification date: 2026-11-15. No production source, test source, configuration, or application data was changed for this phase.

### Runtime safety

- **PASS** — PID 23304 is the Unified QA Runner process for the disposable Phase 3E storage root.
- **PASS** — `127.0.0.1:8081` is owned by PID 23304; no public listener was observed.
- **PASS** — `GET /` returned HTTP 200 and the application HTML.
- **PASS** — Startup stdout/stderr logs were empty; no new startup error was observed.
- **PASS** — No restart, stop, second server, form submission, or real-email operation was performed.

### Browser evidence

- **PASS** — Login page rendered with username/email/mobile and password fields, Sign In, Forgot password, and Create an account controls.
- **PASS** — Registration navigation opened `/register` and rendered the registration form with verification-required guidance.
- **NOT VERIFIED** — Login/logout/session handling; no disposable credentials were available and no account was created under this read-only constraint.
- **BLOCKED** — Settings, Organization, User Story, Sprint/Kanban, Test Case, Defect, Test Execution, CI/CD, Reports, Team, Wiki, Local Runner, and QA Studio authenticated workflows. These require authenticated disposable data and were not safely executable without mutating the running store.

### Unauthenticated API probes

Read-only PowerShell probes returned HTTP 401 for `/api/organizations`, `/api/projects`, `/api/user-stories`, `/api/testcases`, `/api/defects`, and `/api/runs`. This is consistent with protected API enforcement; it is not evidence of authenticated workflow correctness.

### Browser/runtime observations

- No JavaScript exception was observed in the available page snapshot.
- The page reported a CSP-blocked external Google Fonts request. This is a low-severity presentation issue; fallback fonts remained available and no functional failure was established.
- Several unauthenticated bootstrap requests returned 401, consistent with protected endpoints. Endpoint-specific authenticated behavior remains unverified.
- Screenshot capture was attempted but the browser connector rejected the image output; no screenshot artifact is claimed.

### Phase verdict

Authentication entry pages and unauthenticated authorization boundaries are **VERIFIED**. Authenticated module workflows are **BLOCKED / NOT VERIFIED**, not failed. No reproducible application defect was established and no fix was applied. Production readiness remains **NOT APPROVED**. H-005 remains CLOSED; D1-F-001 and D1-F-002 remain OPEN.

## Phase P1-F11 authenticated E2E gate

### Runtime preflight

- **PASS** — Target PID 23304 is present as `java` and its command line identifies `com.example.unifiedqarunner.Main`.
- **PASS** — `127.0.0.1:8081` is listening and owned by PID 23304.
- **PASS** — Root health request returned HTTP 200.
- **PASS** — No restart, stop, second server, configuration change, or data mutation was performed.
- **UNCERTAIN** — Another Java process (PID 22296) exists, but it does not own port 8081; no action was taken.

### Authentication gate

The shared browser was navigated to `/` and shows the login form. No credentials were entered or requested. The signed-in account role, organization, and tenant context therefore remain **NOT VERIFIED**.

Authenticated Settings, Organization, User Story, Sprint/Kanban, Test Case, Defect, security, and accessibility workflows are **BLOCKED** pending user-assisted login. No mutation testing is authorized until a disposable-data plan is presented and explicitly approved.

No new defect was reproduced. Production readiness remains **NOT APPROVED**.

## Phase P1-F11 authenticated read-only verification

The user completed manual login in the shared browser. Read-only navigation was performed without changing application data.

### Results

- **PASS** — Dashboard rendered with signed-in user indicator and sidebar.
- **PASS** — Settings navigation opened the Organization settings view.
- **BLOCKED** — Organization details: no active organization and no authorized organizations were available; selector was disabled.
- **PASS** — Test Case Management shell rendered with empty-state table, search, filters, pagination, and page-size controls.
- **PARTIALLY VERIFIED** — User Story Management route and Overview/Backlog/Epics/Stories/Sprints/Sprint Board controls rendered, but the page reported that a project scope is required.
- **BLOCKED** — Sprint Board content because no project scope was resolved.
- **NOT VERIFIED** — Defect Management; no safe authenticated route/context was available.
- **PARTIALLY VERIFIED** — Navigation to Runner, Wiki, and Database rendered; Database reported no resolved project ownership.

### Security/context observations

The application did not expose an organization or project context to the signed-in account. Several scoped requests returned 403, but no production authorization defect is claimed because the account had no authorized organization/project scope and no second authorized identity was available. No mutation, external runner, pipeline, import, or test execution was invoked.

### Final phase status

Authenticated read-only verification is **PARTIALLY VERIFIED**. The remaining workflows are **BLOCKED** by missing authorized organization/project context. No source changes were made. Production readiness remains **NOT APPROVED**.

## Phase P1-F12A organization/project E2E preflight

Read-only preflight passed for PID 23304, loopback port 8081, and HTTP 200. The authenticated session is active, but the UI reports no active organization, no authorized organizations, and no active organization membership. The organization selector is disabled; the `+ Organization` control is visible and enabled but was not activated.

Source review indicates organization creation requires authenticated CSRF and an authenticated user, while User Story project creation requires same-origin and authenticated-admin authorization. Project archive/restore is available; an organization-delete endpoint was not confirmed. No records were created or changed.

The proposed disposable mutation plan is `QA_E2E_F12_<unique-run-id>` plus `QA_E2E_PROJECT_F12_<unique-run-id>`, with UI/API creation, refresh persistence checks, relationship checks, project archive cleanup, and explicit handling of the non-deletable organization residual. This plan is awaiting explicit approval and has not started.

## P1-F12C browser E2E gap closure

The isolated Selenium run used one disposable server and one browser worker. Final result: **2 targeted tests, 1 PASS, 1 FAIL, 0 ERROR, 0 SKIPPED**. Confirmed regressions are Sprint Board initial render sending no project scope and Test Case creation sending no `requestedProjectId`; the backend rejected both safely. Kanban drag/drop/rollback, browser Test Case/Defect linking, archive/restore, responsive layout, and the complete browser RBAC matrix remain unverified. No production fix was applied. See [p1-f12c-browser-e2e-report.md](./p1-f12c-browser-e2e-report.md).

## Phase P1-F12B isolated integration execution

Authorized isolated execution completed in one bounded Maven process. Results: **34 PASS, 0 FAIL, 0 ERROR, 1 SKIPPED** across 35 targeted tests. Organization browser E2E, organization API, User Story HTTP security, User Story persistence, tenant routes, and cross-tenant HTTP security passed. The skipped `LiveTwoAccountAuthorizationTest` required the disabled `qa.runner.liveBrowser` property.

All test data used temporary roots and was cleaned. PID 23304 and port 8081 remained healthy and untouched. No production source fix was applied. Remaining gaps are full browser User Story mutations/drag-drop and the skipped live-browser authorization mode. Production readiness remains **NOT APPROVED**.

## P1-F12D project-context regression fix

P1-F12C defects were reproduced and fixed only in [src/main/resources/static/index.html](./src/main/resources/static/index.html). Sprint Board now initializes an authorized project scope and omits an empty sprint filter. Test Case creation now requires and submits the authorized project ID; an optional aggregate-statistics 403 is handled without hiding the authorized list. No backend security behavior changed.

Verification: `UserStoryBrowserE2ETest` **2/2 PASS**, `UserStoryHttpSecurityTest` **5/5 PASS**, and `mvn -q -DskipTests compile` **PASS**. The isolated server used disposable storage and an ephemeral loopback port. The live PID 23304 and port 8081 were untouched, so the running build does not yet contain the fix. Production readiness remains **NOT APPROVED**.

## P1-F12E browser integration

The isolated visible User Story Defects tab check passed. The native Kanban drag/drop test encountered a bounded Selenium timeout during post-refresh rehydration, so drag/drop persistence is **NOT VERIFIED** rather than marked failed. Defect creation/linking, archive/restore, rollback, responsive layout, keyboard navigation, and complete browser RBAC remain unverified. See [p1-f12e-browser-integration-report.md](./p1-f12e-browser-integration-report.md). No production source or live data was changed.


## P1-F12F browser gap closure

Test-only corrections removed the fragile board locator, made HTML5 drag/drop deterministic, avoided stale DOM references after re-render, and used the application's existing Defect Management navigation hook. Isolated browser verification passed **3/3** in [P1F12EBrowserE2ETest.java](./src/test/java/com/example/unifiedqarunner/P1F12EBrowserE2ETest.java). [UserStoryHttpSecurityTest.java](./src/test/java/com/example/unifiedqarunner/UserStoryHttpSecurityTest.java) passed **5/5** and compile passed. No production source was changed in this phase. Remaining browser gaps are documented in [p1-f12f-browser-gap-closure-report.md](./p1-f12f-browser-gap-closure-report.md); production readiness remains **NOT APPROVED**.

## P1-F12G final browser/performance verification

The isolated browser class passed **6/6** and the focused User Story HTTP security regression passed **5/5**. Test-only work covered Kanban rollback, keyboard/mobile board behavior, story archive/restore, story-to-test-case linking, and defect visibility. No production source or live data was changed. Project archive/restore and browser two-account RBAC remain **NOT VERIFIED**. See [p1-f12g-browser-performance-fix-report.md](./p1-f12g-browser-performance-fix-report.md).

## P1-F12H project lifecycle and RBAC verification

The isolated browser suite passed **2/2**. Admin project creation/archive/restore and synthetic story/sprint preservation passed. Viewer archive attempts without and with CSRF returned 403, and an Organization A-scoped catalog did not disclose the Organization B project. No production defect was confirmed or fixed. The complete role matrix, organization switching, stale project context, and visual project restore remain **NOT VERIFIED**. Details: [p1-f12h-project-rbac-verification-report.md](./p1-f12h-project-rbac-verification-report.md).

## P1-F12I RBAC, organization switching, and findings audit

## P1-F12J project restore UI and RBAC

## P1-F12K security remediation

## P1-F12K.1 ZIP/media closure update

The ZIP path now has an archive-level ratio guard based on exact archive file bytes and actual decompressed bytes written, in addition to the existing per-entry and total output limits. The new adversarial high-compression test passed. Focused results were **19 PASS, 1 SKIPPED, 0 FAIL, 0 ERROR** across ZIP limits, media streaming, User Story HTTP security, and organization/RBAC tests. D1-F-001 is eligible for separate formal closure review but remains OPEN in the register. D1-F-002 remains OPEN because the Windows symlink case was skipped and direct stream cleanup lacks direct observability. Production readiness remains **NOT APPROVED**. Details: [p1-f12k-security-remediation-report.md](./p1-f12k-security-remediation-report.md).

The media handlers now enforce real-path containment for screenshot/video files, closing the confirmed symlink-following gap without changing authentication, ownership, CSRF, or streaming semantics. Isolated results: media **6 PASS, 1 SKIPPED**, ZIP limits **5/5 PASS**, User Story HTTP security **5/5 PASS**, and RBAC/organization **2/2 PASS**. The symlink runtime case was skipped because the Windows environment did not permit disposable symlink creation; direct disconnect cleanup remains partially observable. D1-F-001 and D1-F-002 remain OPEN, and production readiness remains **NOT APPROVED**. See [p1-f12k-security-remediation-report.md](./p1-f12k-security-remediation-report.md).

The project archive API existed, but the project management UI lacked Active/Archived/All filtering and a restore action. The minimal fix is in [index.html](./src/main/resources/static/index.html), with organization-scoped project loading, archived-safe active context, Admin-only styled lifecycle actions, and bounded double-submit protection. [P1F12JProjectRestoreBrowserE2ETest.java](./src/test/java/com/example/unifiedqarunner/P1F12JProjectRestoreBrowserE2ETest.java) passed **2/2**. `P1F12EBrowserE2ETest` passed **6/6** after a test-only DOM readiness wait; no production defect was attributed to that earlier timeout. PM/PO permissions were checked against the existing server policy. PID 23304 and port 8081 remain untouched; a controlled restart is required to activate the frontend change. Production readiness remains **NOT APPROVED**.

The isolated browser suite passed **2/2**. Source-derived role snapshots for Admin, Project Manager, Product Owner, and Viewer matched the policy; Viewer mutation was denied with 403; switching between two authorized organizations cleared the old context and prevented the prior sprint ID from being requested; and project listings remained organization-scoped. No production defect was confirmed and no production source changed. Project restore remains an API-supported Admin operation without a dedicated frontend restore/discovery control. D1-F-001 and D1-F-002 remain OPEN. Details: [p1-f12i-rbac-organization-security-audit.md](./p1-f12i-rbac-organization-security-audit.md).

## P1-F12K.2 independent security review

The independent review confirmed the current ZIP control is an archive-level actual-byte expansion bound, not exact per-entry compressed-payload accounting. `ZipImportResourceLimitsTest` passed **6/6**. Media real-path containment is source-reviewed and the focused media suite passed **6** with **1 Windows symlink-capability skip**; direct disconnect close events remain only partially observable. `UserStoryHttpSecurityTest` passed **5/5** and the organization/RBAC suite passed **2/2**. Combined focused result: **19 PASS, 1 SKIPPED, 0 FAIL, 0 ERROR**. D1-F-001 and D1-F-002 remain OPEN; H-005 remains CLOSED; production readiness remains NOT APPROVED. See [p1-f12k2-independent-security-review.md](./p1-f12k2-independent-security-review.md).

## P1-F12K.3 final focused verification

The final focused security batch exited 0 with **21 PASS, 1 SKIPPED, 0 FAIL, 0 ERROR**. New ZIP regressions cover multiple entries, a data-descriptor entry, and empty archives. No production source was changed in this phase; the test-only threshold correction addressed the initial fixture failure. D1-F-001 remains OPEN for the per-entry accounting scope decision, and D1-F-002 remains OPEN for Windows symlink execution and direct disconnect observability. Production readiness remains NOT APPROVED.

## P1-F12L controlled restart preflight

Restart readiness is **BLOCKED**. The live process and loopback health remained stable, and the candidate compile/focused tests passed, but the live build is older than the current candidate classes, no verified backup or versioned rollback artifact was established, and authenticated browser verification was unavailable because no authorized shared session was present. No live restart or data mutation was performed. Production readiness remains NOT APPROVED. Details: [p1-f12l-controlled-restart-preflight-report.md](./p1-f12l-controlled-restart-preflight-report.md).

## P1-F12L.0 data preservation and rollback audit

The preservation audit confirms restart readiness remains **BLOCKED**: storage is identified but spans multiple file stores and separate artifacts without a coordinated live snapshot protocol; no backup was created; the current loaded runtime artifact is not reproducibly recoverable; and authenticated browser verification was unavailable. No live data, PID 23304, or port 8081 was modified. Production readiness remains NOT APPROVED. Details: [p1-f12l0-data-preservation-readiness-report.md](./p1-f12l0-data-preservation-readiness-report.md).

## P1-F12L.0A implementation update

The candidate adds configurable root resolution, offline verified backup/restore, release metadata, and schema compatibility validation. Isolated recovery tests passed 6/6; the existing focused persistence/security batch passed 26/26; compile and `git diff --check` passed. The cross-store consistency gate intentionally fails closed because all current persistence writers are not yet registered. No live process, port, storage, or user data was changed. Production readiness remains **NOT APPROVED**.

## P1-F12L.0B coordinated persistence update

The write barrier is now shared per root and integrated with several authoritative stores. Recovery/persistence/security tests passed **32/32**, but direct attachment and durable report filesystem writers are not all coordinated. Backup consistency is therefore **BLOCKED**, and cross-module relationship restore is **NOT VERIFIED**. No live process or data was modified.

## P1-F12L.0B.1 remaining writer integration

Additional JSON persistence writers were coordinated, and SharedPath/RemoteImport saves now preserve unrelated root data. Focused tests remained **32/32 PASS** and compile passed. Direct attachment and durable artifact writers remain uncoordinated; complete cross-module restore is **NOT VERIFIED** and production readiness remains **NOT APPROVED**.

## P1-F12L.0B.2 filesystem writer delta

Attachment publication/deletion and `ReportWriter` summary/index publication now use the shared coordinator; screenshot/video deletion is also coordinated. The isolated focused batch passed **44 PASS, 0 FAIL, 0 ERROR, 1 SKIPPED**. External screenshot/video producers and plugin-specific direct report writers remain outside the barrier, so complete backup consistency and full relationship restore remain **BLOCKED / NOT VERIFIED**. PID 23304 and port 8081 were preserved.

## P1-F12L.0B.3 artifact isolation

Managed staging/publication was added for Robot output and host-generated plugin artifacts. Validation passed **47 PASS, 0 FAIL, 0 ERROR, 1 SKIPPED**. Gatling, Cypress/Newman/JMeter/Playwright-style external outputs and external screenshot/video producers still bypass the staging boundary; full backup consistency remains **BLOCKED** and cross-module recovery remains **NOT VERIFIED**.

## P1-F12L.0B.5 final artifact/recovery delta

Newman, Cypress, WebdriverIO, JMeter CLI, and Playwright staging paths are implemented. The mandatory isolated supported-graph recovery test passed, including manifest/hash verification and restore: the final focused batch was **48 PASS, 0 FAIL, 0 ERROR, 1 SKIPPED** across 49 tests. Gatling and external screenshot/video capture remain uncoordinated or unverified, so complete backup consistency remains **BLOCKED / NOT VERIFIED**. PID 23304 and port 8081 were preserved.

## P1-F12L.0B.4 external runner delta

Newman, Cypress, WebdriverIO, JMeter CLI, and Playwright now use unique staging directories and coordinated host publication after process completion. The latest focused isolated batch passed **47 PASS, 0 FAIL, 0 ERROR, 1 SKIPPED**; compile and `git diff --check` passed. Gatling output and external screenshot/video capture remain uncoordinated, so full cross-module backup/restore acceptance remains **BLOCKED / NOT VERIFIED**. PID 23304 and port 8081 were preserved.
