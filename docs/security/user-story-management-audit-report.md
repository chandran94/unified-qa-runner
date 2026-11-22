# Unified QA Runner - User Story Management Audit

Date: 2026-11-15  
Scope: User Story source, persistence, isolated HTTP checks, bounded regression verification and available browser regression evidence.  
Production readiness: **NOT APPROVED**.

## Safety and baseline

- Branch: `master`.
- HEAD at review start: `4c273b99b237546bcec0b98506e47b39dd3400ec`.
- Existing Organization Settings worktree changes were preserved.
- No restart was performed and port `8081` was not accessed.
- New User Story HTTP tests use disposable `Files.createTempDirectory(...)` roots and `RunnerServer(..., 0, ...)` ephemeral ports.
- No live runtime store or real `qa-runner.json` was used by the focused tests.
- The previously launched disposable application process on an ephemeral port was left untouched.

## Implemented routes and persistence

The implemented User Story API is handled in [RunnerServer.java](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java):

- Project and epic CRUD/archive/restore: `/api/user-stories/projects`, `/api/user-stories/epics`.
- Story list/create/update/status/archive/restore: `/api/user-stories/stories` and story subroutes.
- Sprint CRUD/lifecycle and assignment: `/api/user-stories/sprints` and `/api/user-stories/sprints/{id}/stories`.
- Board list: `/api/user-stories/board`.
- Activity/comments: story `/activity`, `/comments`, and `/comments/{id}`.
- Attachments: story `/attachments`, attachment GET/DELETE.
- Related links: story `/related-links`, related-link PUT/DELETE.
- Test-case links: story `/test-cases` and aggregate `/api/user-stories/test-case-links`.

[UserStoryStore.java](../../src/main/java/com/example/unifiedqarunner/UserStoryStore.java) preserves User Story data in the existing `qa-runner.json` compatibility document, retains unknown root sections, validates project/epic/sprint relationships, writes through a temporary file, and preserves activity/history records.

## Defects found and fixes

### Aggregate test-case story links were not tenant-filtered - FIXED AND TESTED

The aggregate `/api/user-stories/test-case-links` handler now filters each linked story through `tenantVisibleProject(exchange, story.projectId())` before serialization.

[UserStoryHttpSecurityTest.java](../../src/test/java/com/example/unifiedqarunner/UserStoryHttpSecurityTest.java) proves each organization sees only its own link and that an unauthenticated request receives `401`.

### Comment mutation did not enforce author/admin ownership - FIXED AND TESTED

Comment PUT/DELETE now invokes `requireCommentMutationAccess`, allowing the comment author or global administrator and returning `403` for another project user. The isolated HTTP test verifies denied edit/delete and unchanged persisted activity.

### Defect integration could escape an isolated project root - FIXED AND TESTED

[DefectManager.java](../../src/main/java/com/example/unifiedqarunner/DefectManager.java) retains the default constructor for production compatibility and supports an injected project-root path. [RunnerServer.java](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java) supplies the server root, and story/test-case validation uses that injected root. The defect list handler also treats an omitted optional `projectId` as an empty filter instead of dereferencing null.

The focused HTTP test verifies story-linked defect creation, authorized listing, cross-tenant non-disclosure, cross-tenant link denial, isolated-server reload persistence and unlink.

## Feature verification matrix

| Area | Result | Evidence and limitation |
|---|---|---|
| Project/story persistence, IDs, pagination, archive/restore | PARTIALLY VERIFIED | `UserStoryStoreTest` exercises persistence and reload; full HTTP CRUD matrix was not executed. |
| Story fields and creator authority | PARTIALLY VERIFIED | Store tests cover persistence and sanitization; no isolated browser form test. |
| Sprint creation, lifecycle, assignment and project relationship | PARTIALLY VERIFIED | Store and security tests cover lifecycle/project mismatch; no browser workflow test. |
| Kanban status persistence and cross-tenant route authorization | FIXED AND TESTED | Isolated HTTP test moves Org A story to Testing, verifies board state, and confirms Org B cannot move it. Failed-drop UI rollback and duplicate-request behavior were not executed. |
| Comments add/edit/delete/blank validation | FIXED AND TESTED | Store tests and isolated HTTP author/tenant denial coverage passed. Admin moderation was source-reviewed, not separately executed. |
| Acceptance criteria/details tabs and unsaved-value preservation | PARTIALLY VERIFIED | Backend persistence/source wiring reviewed; browser runtime not independently executed. |
| Attachments and related links | PARTIALLY VERIFIED | Synthetic upload, byte-exact download, cross-tenant read/delete denial, reload persistence and deletion passed. Invalid type/size/traversal matrix and related-link UI were not executed. |
| Defect integration | FIXED AND TESTED | Story-linked create/list/link-denial/reload/unlink passed in an isolated HTTP server. Full defect edit UI and every invalid-input branch were not executed. |
| Test-case linking | FIXED AND TESTED | Tenant-filtered aggregate-link HTTP test passed; story-level link source was reviewed. Test-case records lack an independent project field, so proof remains story-authoritative. |
| Authentication, RBAC, tenant isolation, CSRF | PARTIALLY VERIFIED | Focused and existing regressions passed; no full User Story endpoint matrix was run. |
| User Story browser UI, search/filter/pagination, modals, stale refresh | NOT VERIFIED | The available browser test covers Organization Settings only. No User Story browser E2E class was executed; live app interaction was not performed. |

## Commands and results

All Maven commands were sequential and used `MAVEN_OPTS=-Xmx512m -XX:MaxMetaspaceSize=192m`.

1. `mvn -q -DskipTests compile` - **PASS**.
2. `mvn -q '-Dtest=UserStoryStoreTest,UserStoryHttpSecurityTest' test` - **16/16 PASS**, 0 failures, 0 errors, 0 skipped. `UserStoryHttpSecurityTest` contributed 5 tests and used disposable roots/ephemeral ports.
3. `mvn -q '-Dtest=UserStoryStoreTest,UserStoryHttpSecurityTest,OrganizationStoreTest,OrganizationApiIntegrationTest,TenantContextResolverTest,TeamAuthorizationTest,CrossTenantHttpSecurityVerificationTest,AuthenticationFoundationTest,Level417DCompletionTest' test` - **49/49 PASS**, 0 failures, 0 errors, 0 skipped.
4. Inline JavaScript syntax validation of [index.html](../../src/main/resources/static/index.html) - **PASS**, 1 inline script block parsed.
5. `mvn -q '-Dtest=OrganizationBrowserE2ETest' test` - **4/4 PASS**, isolated Selenium/ephemeral-server organization UI regression. A non-fatal CDP-version warning was emitted.
6. `git diff --check` - **PASS**.

The browser test is not counted in the 49-test User Story regression total because it covers the separate Organization Settings relocation. No live app browser verification was performed.

## Files changed in the current worktree

User Story audit changes:

- [RunnerServer.java](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java) - tenant-filter aggregate links, comment ownership enforcement, injected defect manager construction, and null-safe optional defect-list project filtering.
- [DefectManager.java](../../src/main/java/com/example/unifiedqarunner/DefectManager.java) - explicit project-root storage path with unchanged default-constructor compatibility and injected-root validation.
- [UserStoryHttpSecurityTest.java](../../src/test/java/com/example/unifiedqarunner/UserStoryHttpSecurityTest.java) - five disposable HTTP security/persistence tests covering links, comments, attachments, defects and Kanban status.
- This report.

Pre-existing worktree changes preserved and not attributed to this audit:

- [index.html](../../src/main/resources/static/index.html).
- [OrganizationBrowserE2ETest.java](../../src/test/java/com/example/unifiedqarunner/OrganizationBrowserE2ETest.java).
- `.qa-organizations.json` and `.qa-organizations.json.lock`.
- `level4.1.8d-organization-ui-relocation-report.md`.

No commit, push, reset, clean, deploy, or live-server restart was performed.

## Phase 3 live browser verification

- Java process inspection found no running Unified QA Runner process.
- Port `8081` had no listener at inspection time.
- Browser access to `http://localhost:8081/` failed because no live instance was available.
- Latest-source `mvn -q -DskipTests compile` completed successfully with exit code `0`.
- A single authorized startup attempt used `com.example.unifiedqarunner.Main --port=8081` with an isolated temporary root under the system temporary directory. The Java debug launcher did not establish a session and no process or listener remained afterward.
- The current worktree contains uncommitted source changes; the live-build contents therefore cannot be established.
- No second launcher, retry, or unrelated-process termination was attempted.
- Live browser workflows for User Story CRUD, sprint integration, Kanban drag/drop, comments, attachments, defect links and test-case links are **NOT VERIFIED**.

## Phase 3C startup recovery

- Java: OpenJDK `25.0.5.0.2` (Microsoft), `javac` same version.
- Maven: `3.9.11`; compiler release is `17`.
- Entry point: `com.example.unifiedqarunner.Main`.
- No VS Code `launch.json` was present.
- Latest source compiled with `mvn -q -DskipTests compile` and exit code `0`.
- One normal launch used the resolved Maven runtime classpath, compiled `target/classes`, `--port=8081`, and a disposable temporary storage root.
- Startup output reported `Unified QA Runner is available at http://127.0.0.1:8081/`.
- The process was PID `8484`; readiness returned HTTP `200` for `/`.
- Source startup logic requires `QA_RUNNER_LOCAL_ONLY=true` for a fixed port to bind loopback. That variable was not set, so the socket inspection showed `0.0.0.0:8081`, which did not satisfy the localhost-only condition.
- PID `8484` was stopped because it was the task-owned process and was not safely isolated. No retry was made. Port `8081` is currently free.
- Browser E2E was not executed because the only authorized launch did not meet the localhost-only safety gate.

## Phase 3D secure retry

- Port `8081` was confirmed free before the retry.
- The authorized retry created a new disposable storage root and used the latest compiled `target/classes` plus the resolved Maven runtime classpath.
- The launch command construction failed to apply the required `QA_RUNNER_LOCAL_ONLY=true` environment assignment before process creation. The task-owned PID `2180` was stopped immediately; no browser request was sent.
- A second retry was not attempted because Phase 3D authorized only one additional startup attempt.
- Port `8081` is currently free, and no application process from this phase remains.
- Secure startup: **FAIL**. Browser E2E: **NOT RUN**. Production readiness remains **NOT APPROVED**.

## Open limitations and readiness

- A dedicated isolated User Story browser E2E suite for CRUD, sprint UI, Kanban drag/drop rollback, comments, attachments, defect linking and test-case linking remains outstanding.
- Attachment invalid-type/size/traversal, failed-operation cleanup, defect edit UI, sprint mapping UI, comments UI, and attachment/defect/test-case linking UI remain unverified.
- `D1-F-001` and `D1-F-002` remain open; this audit does not change their status.
- `H-005` remains CLOSED.
- Production readiness remains **NOT APPROVED**.

Overall User Story Management classification: **PARTIALLY VERIFIED**. The earlier confirmed security defects and the isolated defect-list null failure were fixed and tested, and attachment/defect/Kanban backend paths gained focused coverage. The complete feature cannot be marked VERIFIED because the requested User Story browser E2E suite and several negative/UI workflows were not executed.

## Phase 3E secure local startup

- `QA_RUNNER_LOCAL_ONLY` was set and verified in the same PowerShell process that created the Java child process: `true`.
- Pre-launch checks passed: port `8081` was free, `target/classes/com/example/unifiedqarunner/Main.class` existed, and the resolved Maven runtime classpath was present.
- The latest compiled classes were launched with `com.example.unifiedqarunner.Main`, `--port=8081`, bounded JVM settings (`-Xmx512m -XX:MaxMetaspaceSize=192m`), and the disposable storage root:
  `C:\Users\elayachandran\AppData\Local\Temp\vscode-agent-codex-sandbox-EfA3Uy\qa-runner-phase3e-32733901852845dfa3d62916084bc0e1`.
- Task-owned application PID: `20088`.
- Startup readiness returned HTTP `200` for `http://127.0.0.1:8081/`.
- Socket inspection confirmed `127.0.0.1:8081 LISTENING` for PID `20088`; no wildcard or non-loopback listener was observed.
- Startup output was recorded in the task-owned temporary root (`startup.out.log` and `startup.err.log`); stderr was empty and stdout reported the application available at `http://127.0.0.1:8081/`.
- The shared browser reached the login page successfully. Unauthenticated API calls returned `401` as expected; external Google Fonts were blocked by the page CSP and were not required for application readiness.
- Authenticated User Story browser E2E was **NOT RUN**. The disposable store contains no synthetic user, and creating one would require an additional provisioning process or application restart not covered by this single startup attempt. No credentials were guessed or real data used.
- The isolated application remains running for further explicitly approved verification. No production data, port `8081` process, commit, push, or deployment was altered.

Phase 3E classification: secure local startup **PASS**; browser readiness/login page **PASS**; authenticated User Story E2E **BLOCKED — no isolated synthetic identity**; production readiness remains **NOT APPROVED**.

## Login failure audit

### Diagnosis

- The running task-owned process is PID `20088`, launched from the latest compiled classes with the disposable Phase 3E storage root. It remains running on loopback only.
- Active storage root: `C:\Users\elayachandran\AppData\Local\Temp\vscode-agent-codex-sandbox-EfA3Uy\qa-runner-phase3e-32733901852845dfa3d62916084bc0e1`.
- Active user database: the `authUsers` section of that root's `qa-runner.json`.
- Account exists in active storage: **false**. The file has no `authUsers` section; no user record was copied or modified.
- This is classification **A — account absent from isolated storage / database mismatch**, not a password-verification defect.

### Authentication flow reviewed

- The frontend submits JSON `{username, password}` to `POST /api/auth/login`.
- [RunnerServer.java](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java) requires same-origin protection, looks up username/email/mobile through [AuthUserDirectory.java](../../src/main/java/com/example/unifiedqarunner/AuthUserDirectory.java), and authenticates only an `ACTIVE` account with a verified email/mobile and a valid PBKDF2-HMAC-SHA256 password hash.
- Unknown, inactive, unverified, or password-mismatched accounts intentionally receive the same generic `401 Invalid username or password.` response. This avoids account-state disclosure.
- Username, email and mobile lookup normalization is implemented; session creation occurs only after successful authentication.
- No authentication source change was required or made. Passwords, hashes, cookies, tokens and personal account data were not printed.

### Safe recovery and verification status

- The application was not restarted, stopped, or redirected to the original storage.
- No production or prior-session user database was copied into the disposable root.
- A real browser login with a synthetic account was **NOT VERIFIED**, because the current process has no synthetic account and provisioning one would require an additional controlled registration/verification or restart step.
- No safe login fix is indicated. The correct recovery is to provision a separate verified synthetic account in the isolated environment through the supported registration flow, with an approved verification-delivery setup, or explicitly authorize a new isolated startup configured for that purpose.
- Current process health remains unchanged: PID `20088`, loopback listener `127.0.0.1:8081`, HTTP readiness `200`.

Login audit result: **NOT FIXED — no application defect confirmed; isolated account provisioning required**. Production readiness remains **NOT APPROVED**.

## Phase 3F synthetic account provisioning

- The existing supported browser registration flow was used with a synthetic `example.com` contact and non-production credentials. No credential values were logged or included in this report.
- Registration reached `POST /api/auth/register` and the application returned HTTP `503` with the controlled message `Verification delivery is not available. Please try again later.`
- The application cleanup path removed the pending account after delivery failure. Active storage now reports `authUsers` present with count `0`.
- The running process was not restarted or stopped. PID `20088` remains healthy on `127.0.0.1:8081`.
- Browser login, email login, invalid-password verification, session/logout checks and User Story smoke tests remain **NOT VERIFIED**, because no verified synthetic account could be provisioned.
- This result confirms the environment lacks a configured test-safe email/SMS delivery channel. It does not justify bypassing verification, inserting hashes, copying a real account, or changing authentication code.
- A future isolated run must start with an explicitly approved development verification adapter or another supported test delivery mechanism, before account provisioning and authenticated browser testing can proceed.

Phase 3F classification: synthetic registration **BLOCKED — verification delivery unavailable**; authentication defect **NOT CONFIRMED**; application remains running and isolated; production readiness remains **NOT APPROVED**.

## Phase 3G development verification delivery audit

- The application has an existing test-only [DevelopmentVerificationDelivery.java](../../src/main/java/com/example/unifiedqarunner/DevelopmentVerificationDelivery.java) provider. It captures OTPs in memory for injected/test use and does not expose them through public API responses.
- Provider selection occurs during [AuthVerificationManager.java](../../src/main/java/com/example/unifiedqarunner/AuthVerificationManager.java) construction. It requires all of the following process environment values:
  - `QA_RUNNER_AUTH_DEV_VERIFICATION=true`
  - `QA_RUNNER_ENV=development`
  - `QA_RUNNER_LOCAL_ONLY=true`
- The current PID `20088` was started with local-only binding but without the development verification settings. Its configured fallback therefore fails closed with `Verification delivery is not configured.` and the registration endpoint returns HTTP `503`.
- The provider cannot be enabled safely through the current HTTP API or browser without changing process configuration. Enabling it requires a new application process/restart.
- Per the Phase 3G safety boundary, PID `20088` was not stopped or restarted. No production code, authentication defaults, credentials, or storage records were changed.
- Synthetic registration, verification, browser login, session/logout, and authenticated User Story smoke tests remain **NOT RUN / BLOCKED — restart required to select the approved development provider**.

Required future isolated launch configuration (not executed): set the three variables above in the same child-process environment, retain the disposable storage root, and then repeat normal registration through the public verification endpoint. OTP completion must still use the captured development-provider delivery through an approved test seam; no hardcoded code or direct `authUsers` edits are permitted.

Phase 3G classification: existing development provider **AVAILABLE BUT NOT ACTIVE**; restart required **YES**; browser authentication **BLOCKED**; application PID `20088` remains running on `127.0.0.1:8081`; production readiness remains **NOT APPROVED**.

## Phase 3H controlled restart and development verification

- Pre-restart identity gate passed: PID `20088` was the expected `com.example.unifiedqarunner.Main` process, owned `127.0.0.1:8081`, and its command line contained the established disposable Phase 3E storage root.
- Only PID `20088` was stopped. No unrelated process or production data was touched.
- The isolated application restarted once as PID `18360` using the same disposable root and latest compiled classes.
- The launch environment was set before Java creation and verified in the launching PowerShell process:
  - `QA_RUNNER_AUTH_DEV_VERIFICATION=true`
  - `QA_RUNNER_ENV=development`
  - `QA_RUNNER_LOCAL_ONLY=true`
- Startup succeeded with HTTP `200`. Socket inspection confirmed `127.0.0.1:8081 LISTENING` owned by PID `18360`; no wildcard listener was observed.
- Synthetic registration through the normal browser/API flow succeeded to the verification-challenge stage. The application displayed the masked example-domain destination and a six-digit OTP form, confirming that the development delivery provider was selected and the account remained unverified until OTP confirmation.
- Verification could not be completed safely: [DevelopmentVerificationDelivery.java](../../src/main/java/com/example/unifiedqarunner/DevelopmentVerificationDelivery.java) stores the generated code in a private in-memory map, while the normal browser/API flow exposes no retrieval endpoint. Extracting it from process memory, adding a backdoor endpoint, fabricating a code, or editing `authUsers` would violate the approved boundary.
- Browser login, logout/session invalidation, CSRF checks after authentication, and authenticated User Story E2E remain **BLOCKED — OTP retrieval seam unavailable**. No authenticated account or User Story record was created.
- The browser surfaced an existing Chromium pattern-validation warning for the username character class; this did not prevent the registration API from reaching the verification stage and was not changed because this phase authorizes no frontend fix.
- Current application health: PID `18360`, disposable storage, loopback-only listener, HTTP readiness `200`. The restarted isolated application remains running.

Phase 3H classification: controlled restart **PASS**; development provider activation **PASS**; synthetic registration **PASS to verification stage**; OTP verification/login **BLOCKED — no supported code retrieval**; authenticated User Story E2E **NOT RUN**; production readiness remains **NOT APPROVED**.

## Phase 3I secure OTP capture assessment

- No production implementation was required. The existing [DevelopmentVerificationDelivery.java](../../src/main/java/com/example/unifiedqarunner/DevelopmentVerificationDelivery.java) already provides a package-private `code(channel, destination)` test hook. It is available to same-package in-process tests only, does not create an HTTP endpoint, does not log OTPs, and keeps values in volatile in-memory state.
- Existing [AuthVerificationManagerTest.java](../../src/test/java/com/example/unifiedqarunner/AuthVerificationManagerTest.java) and [LocalTwoAccountSecurityHarnessTest.java](../../src/test/java/com/example/unifiedqarunner/LocalTwoAccountSecurityHarnessTest.java) exercise captured OTP verification, expiry/attempt limits, resend throttling, and single-use behavior through injected delivery adapters.
- Focused command: `mvn -q -Dtest=AuthVerificationManagerTest,LocalTwoAccountSecurityHarnessTest,UserStoryStoreTest,UserStoryHttpSecurityTest test` — **21/21 PASS**, exit code `0`.
- Browser regression command: `mvn -q -Dtest=OrganizationBrowserE2ETest test` — **4/4 PASS**, exit code `0`, isolated ephemeral server and injected test delivery. Selenium emitted only a non-fatal CDP version warning.
- Combined unique tests in this Phase 3I validation: **25/25 PASS**, 0 failures, 0 errors, 0 skipped. No test touched the live port `8081` or production data.
- The live PID `18360` was not restarted or modified. Its browser OTP challenge remains unconsumable because the running provider has no supported browser/API code retrieval surface; the in-process test hook cannot be attached to that already-running process.
- Live browser registration, OTP confirmation, login/logout, and authenticated User Story E2E therefore remain **NOT VERIFIED / BLOCKED**. No browser-accessible OTP endpoint was added.
- `git diff --check` passed. No production source, test source, configuration, dependency, account, or credential changes were made by this phase.

Phase 3I classification: existing in-process OTP capture **VERIFIED by tests**; live-process OTP capture **BLOCKED — requires an isolated test harness or another explicitly approved restart**; production readiness remains **NOT APPROVED**.

## Phase 3J Gmail OTP delivery configuration audit

### Existing provider and selection

- The supported real-email provider is [SmtpVerificationDelivery.java](../../src/main/java/com/example/unifiedqarunner/SmtpVerificationDelivery.java), selected by [AuthVerificationManager.java](../../src/main/java/com/example/unifiedqarunner/AuthVerificationManager.java) during application construction.
- Email provider precedence is SMTP first, then the configured HTTPS webhook fallback, then the development-only in-memory adapter. The development adapter does not override SMTP when SMTP is configured.
- SMTP delivery uses bounded five-second connection setup and ten-second I/O timeouts, STARTTLS/SSL validation, and fail-closed errors. OTPs and credentials are not returned or logged.
- Registration is handled by `POST /api/auth/register`; verification confirmation is handled by `POST /api/auth/verification/confirm`. Delivery failure removes the pending account and returns a controlled `503` response.

### Supported Gmail configuration

The supported process environment variable names are documented in [README.md](../../README.md):

- `QA_RUNNER_SMTP_HOST` — expected Gmail host `smtp.gmail.com`.
- `QA_RUNNER_SMTP_PORT` — expected `587`.
- `QA_RUNNER_SMTP_USERNAME` — Gmail sender account.
- `QA_RUNNER_SMTP_PASSWORD` — Gmail App Password; presence not inspected by value.
- `QA_RUNNER_SMTP_FROM` — sender address.
- `QA_RUNNER_SMTP_STARTTLS` — expected `true`.
- `QA_RUNNER_SMTP_SSL` — expected `false` when STARTTLS is used.

### Current versus previous setup

- No Gmail SMTP variables are present in the current user or machine environment scopes. The current PID `18360` was launched with the development verification variables and no SMTP variables in the launch command.
- The current isolated storage root is disposable and independent from prior sessions; it contains no prior registered users that can be reused.
- The repository contains provider documentation and implementation, but no safe source-controlled evidence of Gmail credentials. Git history confirms documentation of the variable contract, not that a prior secret configuration is still available.
- The Phase 3H registration challenge was therefore produced by the development provider; no real email was sent during this audit.

### Restoration requirements and safe plan

- Restoring Gmail requires supplying all required SMTP values as process environment variables, with the password supplied as a Gmail App Password through a secret-safe local process environment. Values must not be placed in source, `qa-runner.json`, `.env`, reports, or logs.
- Because provider selection occurs during `AuthVerificationManager` construction, a restart is required. PID `18360` was not stopped or restarted in this audit.
- A future explicitly approved verification run should use a disposable storage root, loopback-only binding, an explicitly authorized test Gmail recipient, bounded SMTP timeouts, and the normal registration/OTP confirmation/login flow. It must not use customer accounts or expose OTPs.
- External email delivery was **NOT TESTED**. No real email was sent and no external integration was accessed.

Phase 3J classification: existing Gmail SMTP implementation **SOURCE-VERIFIED**; required configuration **NOT PRESENT** in current environment scopes; restoration **REQUIRES SECRET-SAFE CONFIGURATION AND RESTART**; current application remains running on PID `18360` at `127.0.0.1:8081`; production readiness remains **NOT APPROVED**.

## Phase 3K business Gmail restoration gate

- Read-only preflight passed for the current task-owned process: PID `18360` is `com.example.unifiedqarunner.Main`, owns `127.0.0.1:8081`, and uses the established disposable Phase 3E storage root. Latest compiled classes and the runtime classpath file are present.
- The existing SMTP provider and selection rules remain unchanged. Gmail requires the documented SMTP variables, with port `587`, STARTTLS enabled, SSL disabled, and a Gmail App Password rather than the normal account password.
- SMTP readiness is **BLOCKED**: no authorized recipient Gmail address was supplied in this task, and no safe secret-injection mechanism is available in this session that would keep the sender address, App Password, and related values out of chat, shell history, process arguments, reports, and logs.
- No SMTP variables were set, no email was sent, no Gmail inbox was accessed, and PID `18360` was not stopped or restarted.
- Required next action: the operator must configure the SMTP variables privately in the launch environment using a secret manager or an interactive local launcher, confirm the single authorized recipient outside chat, then explicitly authorize the one restart. Do not place credentials in source, `.env`, `qa-runner.json`, or command-line arguments.
- Real Gmail OTP delivery, OTP confirmation, browser login, and User Story smoke testing are **NOT RUN**. No retry or second restart was attempted.

Phase 3K classification: preflight **PASS**; secure SMTP configuration **BLOCKED — recipient and secret-safe injection unavailable**; restart/email/browser E2E **NOT RUN**; PID `18360` remains healthy and loopback-bound; production readiness remains **NOT APPROVED**.

## Phase 3L secure Gmail SMTP launcher preparation

- Prepared launcher: [phase3l-gmail-smtp-launcher.ps1](../../scripts/phase3l-gmail-smtp-launcher.ps1).
- Prepared usage guide: [phase3l-gmail-smtp-launcher-usage.md](./phase3l-gmail-smtp-launcher-usage.md).
- Prepared controlled restart checklist: [phase3l-gmail-smtp-controlled-restart-checklist.md](./phase3l-gmail-smtp-controlled-restart-checklist.md).
- The launcher uses the verified `com.example.unifiedqarunner.Main` entry point, `target/runtime-classpath.txt`, `target/classes`, the existing disposable Phase 3E storage root, and `--port=8081`.
- It prompts interactively for the business sender and masked Gmail App Password, passes them only through the intended child environment, never puts the password in Java arguments, and clears temporary launcher environment references after spawning.
- It sets SMTP host/port/STARTTLS/SSL values, `QA_RUNNER_LOCAL_ONLY=true`, and disables the development provider with `QA_RUNNER_AUTH_DEV_VERIFICATION=false`. It refuses occupied port 8081 and refuses non-temporary storage.
- Static PowerShell parser validation: **PASS**, `POWERSHELL_PARSE_ERRORS=0`. Environment names, provider compatibility, storage guard, port guard, and secret-handling paths were inspected.
- The launcher was **not executed**. No restart occurred, no email was sent, and no Gmail credentials or recipient address were handled.
- PID `18360` remains running on `127.0.0.1:8081`. No production data, accounts, source authentication behavior, commit, push, or deployment was changed.

Phase 3L classification: launcher **READY FOR EXPLICITLY APPROVED MANUAL EXECUTION**; execution/email/browser E2E **NOT RUN**; production readiness remains **NOT APPROVED**.

## Phase 3M controlled restart and real Gmail OTP gate

- Read-only preflight: **PASS**. PID `18360` still belongs to `com.example.unifiedqarunner.Main`, owns `127.0.0.1:8081`, uses the disposable Phase 3E storage root, and has the latest compiled classes and runtime classpath available.
- Launcher safeguards: **PASS**. The Phase 3L launcher parses successfully, contains no hardcoded secret literal, checks loopback/port/storage safety, and uses masked interactive App Password input without command-line credentials.
- Controlled restart: **NOT RUN**. The process was not stopped or restarted.
- Real Gmail OTP: **NOT RUN**. No recipient address was confirmed in this session, and no App Password can be entered through a private secret channel available to this session. No email was sent and Gmail was not accessed.
- Browser authentication and User Story smoke testing: **NOT RUN**, because the SMTP restart and real verification prerequisite were not completed.
- Current process remains unchanged and healthy by read-only inspection: PID `18360`, loopback listener `127.0.0.1:8081`, disposable storage preserved.
- Safe next action: run [phase3l-gmail-smtp-launcher.ps1](../../scripts/phase3l-gmail-smtp-launcher.ps1) locally in a trusted terminal, enter the sender and App Password privately, confirm the single recipient outside this chat, and obtain separate authorization before the one controlled restart. Never place credentials in chat, source, `.env`, `qa-runner.json`, logs, or command-line arguments.

Phase 3M classification: preflight **PASS**; secure credential/recipient gate **BLOCKED**; restart, email delivery, OTP confirmation, browser login, and User Story E2E **NOT RUN**; H-005 remains **CLOSED**, D1-F-001 remains **OPEN**, D1-F-002 remains **OPEN**, and production readiness remains **NOT APPROVED**.

## Phase 3O Gmail OTP delivery root-cause audit

- PID and network safety: **PASS**. PID `1264` is `java.exe` running `com.example.unifiedqarunner.Main` with the isolated Phase 3E storage root, and it owns only the loopback listener `127.0.0.1:8081`. No restart, retry, HTTP registration request, or email was performed during this audit.
- Provider selection is source-verified. `AuthVerificationManager.configuredEmailDelivery()` selects `SmtpVerificationDelivery.fromEnvironment()` whenever any SMTP setting is present; the development adapter is only selected through the explicitly enabled development fallback. The process environment values were not printed or disclosed.
- The SMTP implementation uses Gmail host/port settings supplied by the launcher, required authentication, STARTTLS when enabled, five-second connection timeout, ten-second I/O/write timeouts, and propagates `Transport.send` failures without logging secrets.
- Registration behavior is intentional and generic. For a newly created account, `POST /api/auth/register` calls `authVerificationManager.send(...)` synchronously before returning HTTP `202`. A delivery exception causes the pending account to be deleted and HTTP `503` to be returned. For an already registered account, the handler also returns generic HTTP `202` without attempting delivery, to avoid account enumeration.
- Sanitized isolated-storage evidence shows two pending users. Their `USER_CREATED` audit entries have no corresponding `USER_REMOVED_AFTER_DELIVERY_FAILURE` entries. This is consistent with the provider call returning normally for those registrations, but it does not prove Gmail accepted the message or that it reached the inbox.
- Task-owned startup logs contain only normal readiness messages and no SMTP exception. The current implementation does not persist SMTP provider response details or expose a delivery receipt, so inbox placement cannot be established from local evidence.

### Phase 3O classification

Most supported category: **SMTP accepted by the application/provider call, but inbox delivery unconfirmed**. The evidence does not support classifying this as provider-not-selected, duplicate/pending-only, validation/rate limiting, or a confirmed Gmail authentication/TLS failure. Gmail acceptance itself is **NOT INDEPENDENTLY VERIFIED** because no SMTP transaction receipt or Gmail inbox observation is available.

### Safe corrective action and remaining blocker

- No source fix is justified. Do not retry registration or send another email under this audit authorization.
- The user should check the authorized recipient inbox, spam/junk and Gmail filtering for the one already authorized message. If absent, the next approved diagnostic must capture only sanitized SMTP failure categories from a single controlled attempt; it must not log credentials, OTPs, message content, or recipient details.
- A restart is **not required for diagnosis**. It is required only if SMTP configuration/provider selection must be changed. The current PID was not restarted.
- OTP confirmation and browser login remain **NOT VERIFIED** because the OTP was not observed through the normal application flow.

Phase 3O result: registration HTTP `202` is an intentional generic response; the application-level delivery call appears to have completed for newly created pending accounts, but external Gmail delivery remains unconfirmed. No speculative fix was implemented. H-005 remains **CLOSED**, D1-F-001 remains **OPEN**, D1-F-002 remains **OPEN**, and production readiness remains **NOT APPROVED**.

## Phase 3Q secure registration and account recovery implementation

- Existing registration and OTP verification were preserved. OTPs remain cryptographically random, short-lived, hashed in memory, single-use, attempt-limited, and delivered through the existing `VerificationDelivery` abstraction. The existing 60-second resend cooldown remains enforced. Pending email recovery now has a generic email-based resend route; it never returns account existence or a verification token in the API response.
- Added a password-reset manager using cryptographically random single-use reset tokens. Only token hashes are retained in memory; tokens are not persisted. Tokens expire after fifteen minutes, failed/used tokens cannot be replayed, disabled accounts are rejected, and successful reset revokes existing sessions for the account.
- Added generic `POST /api/auth/password-reset/request` and `POST /api/auth/password-reset/confirm` routes. Recovery requests return the same generic `202` response for known and unknown addresses to avoid account enumeration. Both routes enforce same-origin validation.
- Extended the existing SMTP provider with a dedicated password-reset subject/body while preserving the existing verification-message flow. No SMTP email was sent by this implementation or test run.
- Added public reset UI handling for `/forgot-password` and `/reset-password`, including validation, loading state, generic recovery messaging, token entry/query prefill, and password confirmation. No credentials, reset tokens, or OTPs are logged.
- Existing live application PID `1264` was not restarted, so the running process does not contain these source changes. A controlled restart is required before this new recovery flow can be browser-verified in the live instance.

### Phase 3Q verification

- `mvn -q -DskipTests compile`: **PASS**.
- Focused `AuthVerificationManagerTest` and `PasswordResetManagerTest`: **5/5 PASS**.
- Focused `AuthenticationFoundationTest`: **4/4 PASS**.
- Existing `RunnerServerTest` with `-Dqa.runner.port=0`: **3/3 PASS**. The initial default-port attempt was not used because it correctly conflicted with the protected live listener on 8081.
- Combined reported focused result: **12/12 PASS**, excluding the command-line parsing attempt and the intentionally blocked default-port run.
- `git diff --check`: **PASS**.
- Live PID/listener remained unchanged: PID `1264`, `127.0.0.1:8081`.

### Phase 3Q limitations and status

- Real Gmail delivery, OTP confirmation, password-reset email delivery, browser recovery, and live User Story smoke testing were **NOT RUN**. Tests used isolated temporary stores and injected/mock delivery only.
- Existing pending accounts in the disposable live store were not modified. No real user data or production storage was touched.
- H-005 remains **CLOSED**. D1-F-001 remains **OPEN**. D1-F-002 remains **OPEN**. Production readiness remains **NOT APPROVED**.

## Phase 3S controlled restart readiness

- Read-only process inspection found PID `1264` still present as `java.exe`. Its sanitized command-line checks matched `com.example.unifiedqarunner.Main`, the `8081` port flag, and a temporary-storage pattern; no SMTP password or password-like command-line value was present.
- The expected Phase 3L launcher, usage guide, and restart checklist exist. The launcher statically enforces the task-owned temporary storage pattern, requires compiled classes and runtime classpath, refuses an occupied port, binds through the existing localhost launch configuration, and accepts SMTP credentials only through masked interactive input.
- The latest compiled `AuthVerificationManager.class` and `RunnerServer.class` are newer than their corresponding source files. `target/classes` and `target/runtime-classpath.txt` exist. This confirms build artifacts are available, but the already-running PID cannot contain these newer classes without a restart.
- **Blocking preflight result:** the current socket query returned no listener for local port `8081`. Therefore PID `1264` cannot currently be confirmed as the owner of the required live listener. No restart, replacement launch, HTTP request, SMTP action, or process termination was attempted.
- Restart readiness: **BLOCKED**. Before any future restart approval, re-confirm that PID `1264` owns `127.0.0.1:8081` (or establish that the process is no longer the active instance through a separately authorized recovery decision), then re-run the full storage and process-identity checklist. Do not stop PID `1264` based on this result.
- Planned post-restart checks remain: loopback binding, HTTP readiness, disposable storage, activation of the new auth endpoints, one authorized real-email flow only, and mocked-delivery rate-limit tests. No email was sent in this phase.
- `git diff --check`: **PASS**. H-005 remains **CLOSED**. D1-F-001 remains **OPEN**. D1-F-002 remains **OPEN**. Production readiness remains **NOT APPROVED**.

## Phase 3S.1 missing-listener diagnosis

- PID `1264` is present as `java.exe`, with creation time `2026-11-15T07:11:53.3719350Z` and start time `2026-11-15T12:41:53.3719357+05:30`. Sanitized identity checks still match `com.example.unifiedqarunner.Main`, the `8081` port flag, and the disposable temporary-storage pattern. No secret-bearing command-line value was detected.
- The first bounded socket query returned no rows, but a subsequent bounded TCP probe succeeded. A follow-up `Get-NetTCPConnection`/`netstat` inspection confirmed `127.0.0.1:8081 LISTENING` with owning PID `1264`; the established connections were also loopback-only.
- A single bounded `GET http://127.0.0.1:8081/` health check returned HTTP `200`. No authentication, registration, OTP, or user data request was made.
- Task-owned startup logs contain no sanitized bind exception, uncaught exception, or shutdown message. The relevant startup output reports normal readiness; the error logs are empty. The earlier empty socket observation is therefore classified as a transient/incomplete observation, not a confirmed application lifecycle failure. PID reuse is not indicated by the current process identity, creation time, command-line matches, and socket ownership, although no independent historical PID record exists.
- Current diagnosis: application **HEALTHY**, listener ownership **CONFIRMED**, loopback binding **CONFIRMED**, storage isolation **CONFIRMED**. A controlled restart is **NOT NECESSARY for this diagnosis**; it remains separately approval-gated if the latest authentication changes must be activated.
- Recommended next action: if restart approval is later granted, re-run the full ownership and storage checklist immediately before stopping anything, then use the reviewed Phase 3L launcher for one controlled restart. Do not send Gmail OTP or restart based solely on the earlier transient query.
- No process was stopped or started, no email was sent, no production data was modified, and no commit or push was performed. H-005 remains **CLOSED**. D1-F-001 remains **OPEN**. D1-F-002 remains **OPEN**. Production readiness remains **NOT APPROVED**.

## Phase 3R.1 OTP resend abuse protection and isolated authentication E2E

### Resend audit and fix

- Before this phase, `AuthVerificationManager` enforced only the 60-second per-challenge cooldown. It did not enforce a bounded rolling resend window across repeated cooldown periods.
- Added synchronized resend admission shared by token-based and email-based resend paths.
- Added an account limit of **5 resend operations per one-hour window** and a source-address limit of **20 resend operations per one-hour window**.
- Expired rate-window entries are removed on access; at most 4096 rate keys are retained, preventing unbounded in-memory growth. Manager shutdown clears both challenges and rate state.
- Rate admission occurs before delivery, so a rate-limited request does not trigger SMTP or mock delivery. Public email-resend responses remain generic and do not reveal account state.
- The existing 60-second cooldown, previous-challenge replacement, OTP hashing, expiry, attempt limit, single-use behavior, SMTP integration, CSRF checks, RBAC, and tenant authorization were preserved.
- The limits are process-local and do not survive an application restart. Persistent abuse controls would require an approved persistence/rate-limit design and were not added here.

### Verification

- `mvn -q -DskipTests compile`: **PASS**.
- Focused unit/server suite with `-Dqa.runner.port=0`: **13/13 PASS** across `AuthenticationFoundationTest` (4), `AuthVerificationManagerTest` (4), `PasswordResetManagerTest` (2), and `RunnerServerTest` (3).
- `mvn -q -Dtest=AuthenticationRecoveryBrowserE2ETest test`: **2/2 PASS** in an isolated disposable store and ephemeral server. Coverage includes registration, OTP verification, generic duplicate registration, invalid password rejection, successful login, logout, password reset, session invalidation, and new-password login.
- `mvn -q -Dtest=OrganizationBrowserE2ETest test`: **4/4 PASS** in the existing isolated browser regression suite.
- `git diff --check`: **PASS**.

### Safety and decision

- PID `1264` remains running on `127.0.0.1:8081`; it was not restarted, stopped, queried over HTTP, or modified. All new tests used a separate ephemeral port and mocked delivery. No Gmail message was sent.
- The changed production files for this phase are [AuthVerificationManager.java](../../src/main/java/com/example/unifiedqarunner/AuthVerificationManager.java) and [RunnerServer.java](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java). The focused test change is [AuthVerificationManagerTest.java](../../src/test/java/com/example/unifiedqarunner/AuthVerificationManagerTest.java); the expanded existing isolated browser test is [AuthenticationRecoveryBrowserE2ETest.java](../../src/test/java/com/example/unifiedqarunner/AuthenticationRecoveryBrowserE2ETest.java).
- Authentication browser verification is **VERIFIED for the executed isolated scenarios**, not for the old live build. Real SMTP delivery and live-server browser E2E remain **NOT RUN**.
- Controlled restart consideration: **NOT YET APPROVED**. The new code requires a separately authorized restart before it can be exercised by the live instance, and persistent restart-surviving abuse limits remain an architectural limitation.
- H-005 remains **CLOSED**. D1-F-001 remains **OPEN**. D1-F-002 remains **OPEN**. Production readiness remains **NOT APPROVED**.

## Phase 3R isolated authentication-recovery audit

### Scope and safety

- The existing live application was not restarted, stopped, queried, or otherwise interrupted. PID `1264` remained the protected process on `127.0.0.1:8081`.
- No real SMTP credentials, recipient addresses, OTPs, reset tokens, production data, or live account records were used.
- Verification used a separate in-process `RunnerServer` on an ephemeral port, a disposable temporary storage root, and an injected in-memory delivery implementation. The browser test used one headless browser and bounded synthetic data.

### Source audit result

- Registration uses generic HTTP `202` behavior for duplicate accounts and does not send a second message for an already registered address.
- New registration delivery is attempted before the generic success response; delivery failure removes the pending account and returns a controlled failure response.
- OTP challenges are random, hashed in memory, time-limited, attempt-limited, and single-use. Pending-account resend uses a generic response and preserves the 60-second cooldown.
- Password-reset tokens are random, stored only as hashes in memory, expire after fifteen minutes, are single-use, and invalidate the user's existing sessions after a successful reset. Recovery requests are same-origin protected and generic for known and unknown addresses.
- Material remaining gap: the source demonstrates a cooldown but no independently evidenced bounded maximum resend count across repeated cooldown windows. This was not changed in Phase 3R and remains a security-verification/remediation item.
- CSRF, RBAC, tenant isolation, and authentication behavior were not weakened by the recovery changes. The live old build does not contain the new recovery implementation because no restart was authorized.

### Isolated execution evidence

- `mvn -q '-Dtest=AuthenticationRecoveryBrowserE2ETest' test`: **1/1 PASS**. The test requested a reset through the isolated server, captured the token only in the in-process mock delivery, completed reset through the browser UI, confirmed unauthenticated redirect behavior, and logged in with the new password.
- `mvn -q '-Dtest=OrganizationBrowserE2ETest' test`: **4/4 PASS** in the existing disposable, ephemeral-port browser suite. This is organization-navigation evidence, not complete authentication-recovery coverage.
- Focused authentication/recovery tests: **12/12 PASS** across `AuthenticationFoundationTest`, `AuthVerificationManagerTest`, `PasswordResetManagerTest`, and `RunnerServerTest` with `-Dqa.runner.port=0`.
- No real Gmail delivery, live-server login, live registration, or real OTP was executed. Browser coverage for registration, OTP entry/resend, expiry/replay, forgot-password request, and logout was therefore not claimed as complete.

### Phase 3R decision

- Isolated recovery browser verification: **PASS for the executed reset/login scenario**.
- Full Phase 3Q browser E2E: **PARTIALLY VERIFIED**; the resend-window limit and several live/authentication scenarios remain unverified.
- Controlled restart of PID `1264`: **NOT APPROVED** by this audit. Additional approval is required after the resend-limit gap is addressed or explicitly accepted and the remaining recovery scenarios are independently verified.
- `git diff --check`: **PASS**. No production process was interrupted, no email was sent, no commit or push was performed, and port `8081` was untouched.
- H-005 remains **CLOSED**. D1-F-001 remains **OPEN**. D1-F-002 remains **OPEN**. Production readiness remains **NOT APPROVED**.

## Phase 3T controlled restart preflight

- PID `1264` was reverified with creation time `2026-11-15T07:11:53.3719350Z`; sanitized process identity matched `com.example.unifiedqarunner.Main`, port `8081`, and the disposable temporary-storage pattern. No secret-bearing command-line value was detected.
- `127.0.0.1:8081` is owned by PID `1264` and a bounded localhost health check returned HTTP `200`. Binding is loopback-only. The disposable storage root exists with `qa-runner.json`, and it is not the project root.
- The Phase 3L launcher, usage guide, and controlled-restart checklist are present. The latest compiled authentication classes are newer than their corresponding source files; runtime classpath metadata is present.
- Build validation: `mvn -q -DskipTests compile` **PASS**. Focused isolated security/server tests: **13/13 PASS**. Isolated authentication browser tests: **2/2 PASS**. Organization browser regression: **4/4 PASS**. No parallel Maven processes were used.
- Restart approval status: **NOT GRANTED BY THIS PROMPT**. No process was stopped or started, no SMTP credentials were entered, no email was sent, and no live authentication data was modified.
- Expected downtime for a future approved restart: bounded shutdown/startup interval only; exact duration is not guaranteed. Rollback: do not retry automatically; if the task-owned launch fails, retain sanitized logs, stop further action, and restore only the previously confirmed task-owned process state through a separately approved recovery procedure.
- Risks: SMTP secret entry and real Gmail delivery remain external approval gates; the current process runs the older build; resend rate state is process-local and is not persistent across restart. Live browser authentication must be performed only after explicit restart approval.
- Restart checklist presentation: current PID `1264`; socket owner `1264`; storage classification disposable; build/tests verified; launcher ready; expected downtime bounded but not guaranteed; rollback and stop conditions documented. **STOPPED BEFORE RESTART.**

## Phase 4A User Story Management audit

### Scope and safety

- The live application and PID `1264` were not stopped, restarted, killed, or used for feature testing. Port `8081` and live storage were not modified. All executed server tests used temporary storage and dynamically allocated ports; no SMTP or external integration was used.
- Existing architecture was retained. No duplicate User Story store, API, persistence schema, authentication control, RBAC rule, CSRF rule, tenant boundary, or organization navigation implementation was introduced.

### Verified implementation and tests

- `UserStoryStoreTest`: **11/11 PASS** — project/story persistence, validation, sprint behavior, comments/activity, links, and rich-text sanitization.
- `UserStoryHttpSecurityTest`: **5/5 PASS** — tenant-bound test-case links, comment author/admin enforcement, attachment isolation and reload persistence, defect linkage isolation, and Kanban status authorization/persistence.
- `ProjectOwnershipManagerTest`: **2/2 PASS** — ownership immutability and unresolved legacy/shared-resource quarantine.
- `OrganizationStoreTest`: **8/8 PASS**.
- `OrganizationApiIntegrationTest`: **3/3 PASS**.
- Combined focused Phase 4A result: **29/29 PASS**.
- `mvn -q -DskipTests compile`: **PASS**. `git diff --check`: **PASS**.
- The test run emitted a non-failing `sendResponseHeaders` warning for a 204 response and an observed `Runner request failed: stream closed` message; no test failed and no confirmed defect was established from that output.

### Audit classification

- User Story CRUD/persistence: **PARTIALLY VERIFIED** — backend/store coverage passes; complete browser CRUD and restart persistence were not executed against a newly restarted build.
- Sprint assignment and Kanban authorization/persistence: **PARTIALLY VERIFIED** — isolated HTTP/store assertions pass; full browser drag/drop and rollback behavior remain unverified.
- Comments, attachments, test-case links, and defect links: **PARTIALLY VERIFIED** — focused isolated security workflows pass; complete browser UI workflows and all negative cases are not covered by this batch.
- Organization/project authorization and tenant isolation: **SOURCE-REVIEWED plus focused HTTP evidence**; live browser verification remains unavailable without a separate restart/test-account flow.
- Search, filtering, sorting, pagination, responsive layout, keyboard behavior, loading/empty/error UI states, and browser refresh workflows: **NOT VERIFIED** in this batch.

### Defects and change control

- No reproducible P0/P1/P2 defect was confirmed during the bounded audit. No production source, test source, `pom.xml`, authentication, SMTP, persistence schema, or security control was changed for Phase 4A.
- Only this audit report was updated for this phase. Existing unrelated worktree changes were preserved.
- Restart required to activate the accumulated source changes: **YES**. Restart authorization: **NOT GRANTED**. Production readiness: **NOT APPROVED**.
- H-005 remains **CLOSED**. D1-F-001 and D1-F-002 remain **OPEN**.

## Phase 4A premium product experience batch

### Implemented frontend improvements

- [index.html](../../src/main/resources/static/index.html) now provides local-only saved User Story filters (project, priority, and search), a clear-filters action, and an explanatory empty state. Saved filter data contains no credentials, tokens, or tenant records.
- Story search is debounced by 250 ms before requesting results, reducing request churn while preserving the existing `/api/user-stories/stories` contract and pagination behavior.
- The Sprint Board retains the existing project/sprint server query and optimistic status rollback, and adds client-side assignee and priority filters without changing persistence or authorization.
- Board cards now support keyboard Enter to open details and left/right arrows to request adjacent workflow states. The existing status selector remains available as the explicit keyboard-friendly control; server authorization and rollback remain authoritative.

### Verification and boundaries

- JavaScript syntax extraction/check: **PASS** using Node `new Function` parsing; the application was not started and no browser was used.
- `mvn -q -DskipTests compile`: **PASS** with bounded Maven memory settings.
- `mvn -q '-Dtest=UserStoryStoreTest,UserStoryHttpSecurityTest' test`: **PASS**; focused result remained **16/16 PASS** for those two classes. No live storage or port `8081` was accessed.
- `git diff --check`: **PASS**. The Maven test output included the existing non-failing 204/stream-closed warning; no test failure resulted.
- Browser verification of saved filters, debounce behavior, board filters, responsive rendering, and keyboard interaction: **NOT VERIFIED** because the live application was not touched and no separate isolated browser harness exists for this static UI batch.
- No backend, authentication, RBAC, CSRF, tenant authorization, persistence, ZIP, media-streaming, or dependency changes were made for this batch. No production data was modified.

### Readiness

- The premium UX batch is **IMPLEMENTED AND STATICALLY VERIFIED**, with runtime UI behavior still **PARTIALLY VERIFIED** pending a separately authorized isolated restart/browser run.
- Existing accumulated source changes still require a separately approved restart before the live process can contain them. PID `1264`, loopback port `8081`, and its disposable storage were left untouched.
- H-005 remains **CLOSED**. D1-F-001 and D1-F-002 remain **OPEN**. Production readiness remains **NOT APPROVED**. No commit, push, or deployment was performed.

## Phase 4A browser E2E verification

### Isolation

- The live application PID `1264`, loopback port `8081`, live storage, SMTP configuration, and live credentials were not used or interrupted.
- Browser execution used the existing Selenium tests with disposable temporary roots and `RunnerServer` instances on dynamically assigned ports. Tests were run sequentially with one Maven process and bounded JVM memory.

### Results

- `mvn -q '-Dtest=OrganizationBrowserE2ETest,LiveTwoAccountAuthorizationTest' '-Dqa.runner.liveBrowser=true' test`: **5 tests run; 4 passed, 1 failed, 0 errors, 0 skipped**.
- `OrganizationBrowserE2ETest`: **4/4 PASS**, including Settings organization navigation without Dashboard duplication and stale organization-switch response protection.
- `LiveTwoAccountAuthorizationTest.isolatedBrowserSessionsEnforceProjectAccess`: **FAIL** at [LiveTwoAccountAuthorizationTest.java](../../src/test/java/com/example/unifiedqarunner/LiveTwoAccountAuthorizationTest.java:156), where the disposable project-manager fixture expected `GET /api/user-stories/projects` to return `200` but received `403`. The failure occurred before the later story UI assertions. No production authorization change was made; the fixture/authorization-context mismatch requires separate investigation.
- Selenium emitted a non-failing CDP-version warning for the installed Chromium version. Browser resources and isolated servers were cleaned up by the tests.

### UX coverage classification

| Scenario | Classification | Evidence |
|---|---|---|
| Settings organization navigation and stale organization switching | VERIFIED | `OrganizationBrowserE2ETest`, 4/4 PASS |
| User Story backend CRUD, persistence, links, comments, attachments, Kanban authorization | VERIFIED for focused isolated server assertions | `UserStoryStoreTest` 11/11 and `UserStoryHttpSecurityTest` 5/5 PASS |
| Story saved filters and clear action | NOT VERIFIED in browser | Static implementation only; no dedicated User Story browser test exists |
| Debounced search | NOT VERIFIED in browser | Static implementation only |
| Board assignee/priority filters | NOT VERIFIED in browser | Static implementation only |
| Keyboard Enter/arrow board actions | NOT VERIFIED in browser | Static implementation only |
| Drag/drop persistence and failure rollback in browser | PARTIALLY VERIFIED | Existing source and focused HTTP evidence; no dedicated browser workflow |
| Responsive layout, refresh persistence, full story/comment/link workflow | NOT VERIFIED | No complete isolated browser suite |
| Tenant/RBAC/CSRF backend boundaries | PARTIALLY VERIFIED | Focused HTTP tests pass; broader browser authorization run has the documented 403 failure |

### Defects and readiness

- No frontend defect was reproduced by an isolated browser test, so no additional fix was applied in this verification batch.
- The project-manager browser regression is unresolved and was not silently ignored. Its root cause is not established: it may reflect an incomplete organization context in the legacy fixture or an authorization behavior change. It is outside the approved UX-only fix scope.
- Production readiness remains **NOT APPROVED**. A separately authorized isolated User Story browser suite and a separate investigation of the project-manager 403 are required before claiming complete E2E verification.
- H-005 remains **CLOSED**. D1-F-001 and D1-F-002 remain **OPEN**. No restart, commit, push, deployment, live-port access, or live-data modification occurred.

## Phase 4A-F1 Project Manager authorization failure investigation

### Corrected root cause

- The reported failure at the historical line 156 was misattributed. The failing request was the Admin browser's `GET /api/cicd/config`, not the Project Manager's User Story project-list request.
- `RunnerServer.requireCiCdProjectAccess(...)` requires explicit `projectId` and `organizationId` scope before CI/CD access. The legacy fixture called that endpoint without either query parameter, so HTTP **403** was the intended fail-closed result.
- The same legacy fixture created projects only in `TeamMemberManager` and did not create organization ownership or active organization memberships. Tenant-scoped test-case and project-list paths therefore correctly denied or filtered requests. This was a **test-fixture/context defect**, not a Project Manager RBAC defect.
- After the fixture was aligned with the intended tenant model, an additional compatibility issue was exposed: query parameters on `/api/testcases/{id}` were rejected by the global allowlist with HTTP **400** before scoped authorization could run. The minimal fix allows query parameters on test-case subroutes; the existing tenant/project authorization checks remain unchanged.

### Changes in this investigation

- [LiveTwoAccountAuthorizationTest.java](../../src/test/java/com/example/unifiedqarunner/LiveTwoAccountAuthorizationTest.java) now creates disposable organization ownership and active memberships for the browser identities and supplies organization scope on tenant-scoped requests. It also expects the intentionally unscoped CI/CD request to remain **403**.
- [RunnerServer.java](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java) now permits query parameters on `/api/testcases/{id}` and related test-case subroutes so the existing organization-scoped authorization path can receive `organizationId`. No authorization rule, role permission, tenant boundary, storage schema, or live data was changed.

### Verification evidence

- Command: `mvn -q '-Dtest=LiveTwoAccountAuthorizationTest,UserStoryHttpSecurityTest' '-Dqa.runner.liveBrowser=true' test`.
- `LiveTwoAccountAuthorizationTest`: **1/1 PASS** (isolated temporary storage, ephemeral port, headless browser).
- `UserStoryHttpSecurityTest`: **5/5 PASS**.
- Combined final result: **6/6 PASS, 0 failures, 0 errors, 0 skipped**.
- The run used one bounded Maven process. Selenium emitted only the existing non-failing Chromium CDP-version warning; the HTTP server emitted the existing 204/stream-closed diagnostic during cleanup.
- `git diff --check`: **PASS**.

### Scope and readiness

- The authenticated Project Manager's authorized project visibility, cross-project denial, test-case access, and role-escalation denial now execute successfully in the isolated browser regression.
- No live PID `1264`, port `8081`, live storage, SMTP provider, or production account was accessed or interrupted. No commit or push was performed.
- Full Phase 4A browser UX coverage remains **PARTIALLY VERIFIED**: saved-filter browser behavior, complete CRUD refresh flows, drag/drop rollback, responsive layout, and all cross-module browser workflows were not covered by this focused authorization test.
- H-005 remains **CLOSED**. D1-F-001 and D1-F-002 remain **OPEN**. Production readiness remains **NOT APPROVED**.

## Phase 4A-F2 complete User Story browser E2E verification

### Environment and isolation

- Latest source compilation: **PASS** — `mvn -q -DskipTests compile`, exit code `0`.
- Docker: **UNAVAILABLE** (`docker` command is not installed). No Docker infrastructure was needed because the application test harness uses disposable filesystem storage and ephemeral loopback ports.
- Node.js: **AVAILABLE**, v24.14.0. HTML JavaScript syntax extraction/check: **PASS**, one script block parsed with `new Function`.
- Browser tier: existing Java Selenium harness, not Playwright. No Playwright suite exists in this repository; no new browser framework or dependency was added.
- All server/browser tests used temporary roots and dynamically assigned ports. PID `1264`, port `8081`, live storage, live accounts, SMTP, and the live browser page were not touched.

### Executed regression evidence

Command:

`mvn -q '-Dtest=UserStoryStoreTest,UserStoryHttpSecurityTest,OrganizationStoreTest,OrganizationApiIntegrationTest,OrganizationBrowserE2ETest,LiveTwoAccountAuthorizationTest' '-Dqa.runner.liveBrowser=true' test`

| Test class | Result |
|---|---:|
| `UserStoryStoreTest` | 11/11 PASS |
| `UserStoryHttpSecurityTest` | 5/5 PASS |
| `OrganizationStoreTest` | 8/8 PASS |
| `OrganizationApiIntegrationTest` | 3/3 PASS |
| `OrganizationBrowserE2ETest` | 4/4 PASS |
| `LiveTwoAccountAuthorizationTest` | 1/1 PASS |
| **Total** | **32/32 PASS; 0 FAIL; 0 ERROR; 0 SKIP** |

The run exited with code `0`, used one Maven process, one sequential browser test process, temporary data, and cleanup. Selenium emitted a non-failing Chromium CDP-version warning; no test failure resulted.

### Feature classification

| Feature | Classification | Evidence / limitation |
|---|---|---|
| User Story persistence and store CRUD | VERIFIED for focused store coverage | `UserStoryStoreTest`, 11/11 |
| User Story HTTP security, tenant/RBAC/CSRF boundaries | VERIFIED for covered API scenarios | `UserStoryHttpSecurityTest`, 5/5; not a complete browser UI proof |
| Project Manager assigned/unassigned and cross-project authorization | VERIFIED for covered isolated browser/API scenarios | `LiveTwoAccountAuthorizationTest`, 1/1; fixture now includes organization ownership and memberships |
| Organization navigation and stale organization switching | VERIFIED | `OrganizationBrowserE2ETest`, 4/4 |
| Organization persistence/API boundaries | VERIFIED for covered scenarios | `OrganizationStoreTest` 8/8 and `OrganizationApiIntegrationTest` 3/3 |
| Full browser User Story create/edit/delete and refresh persistence | NOT VERIFIED | No dedicated full User Story UI journey exists in the repository |
| Comments UI add/edit/delete | NOT VERIFIED in browser | Backend/security coverage is not equivalent to browser verification |
| Attachments UI upload/download/delete | NOT VERIFIED in browser | No dedicated browser workflow executed |
| Sprint mapping and Sprint Board UI filters | NOT VERIFIED in browser | No dedicated browser workflow executed |
| Kanban drag/drop, rollback, keyboard navigation | NOT VERIFIED in browser | Source/API evidence only; no browser interaction suite |
| Test Case and Defect linking UI | NOT VERIFIED in browser | No complete browser integration flow executed |
| Saved filters, debounce, clear filters, responsive layout | NOT VERIFIED in browser | Static syntax passed; behavioral browser evidence absent |

### Defects and readiness

- No new production defect was reproduced in the executed scope. The earlier authorization failure was resolved through tenant-correct test-fixture setup plus the minimal test-case query-allowlist compatibility fix documented in the Phase 4A-F1 section.
- No unrelated refactoring, dependency, authentication, RBAC, CSRF, tenant-policy, persistence-schema, or live-data changes were made for this verification phase.
- Production readiness remains **NOT APPROVED** because the complete User Story browser workflow requested by F2 remains materially unverified.
- H-005 remains **CLOSED**. D1-F-001 and D1-F-002 remain **OPEN**. No restart, commit, push, or deployment occurred.
