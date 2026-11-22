# Unified QA Runner — P1-F12A.2 Isolated E2E Environment Preparation

## Gate status

**PREPARED, NOT LAUNCHED.** This is a read-only preparation artifact. PID 23304 and port 8081 were not interrupted, and no isolated files, users, organizations, projects, or sessions were created.

## Isolation audit

The supported test construction path is `RunnerServer(Path projectRoot, int port, VerificationDelivery testEmailDelivery, VerificationDelivery testSmsDelivery)`.

- `projectRoot` is passed to the execution service, user-story store, defect manager, team managers, authentication directory, organization store, wiki, ownership manager, CI/CD configuration, database connection service, and shared-path/import configuration.
- The compatibility document is `projectRoot/qa-runner.json`.
- Organization state is `projectRoot/.qa-organizations.json` with its backup and lock files.
- Attachments, screenshots, reports, runner artifacts, imports, and logs resolve beneath the injected project root or its target subdirectories.
- `AuthUserDirectory` resolves users from the injected `projectRoot/qa-runner.json`; it does not fall back when a root is injected.
- `AuthSessionManager` is constructed per server instance, so the isolated server has separate in-memory sessions and cookies from PID 23304.
- The test constructor with port `0` lets the operating system select an ephemeral loopback port. `RunnerServer` binds port 0 to `127.0.0.1`.
- Injected `VerificationDelivery` instances keep OTP delivery in the test process and avoid SMTP or external email delivery. The password-reset delivery also uses the injected email delivery.

The isolated server must not be constructed with the live project root, live `qa-runner.json`, live `.qa-organizations.json`, live attachments, or live import paths.

## Stateful resources and safety controls

| Resource | Isolated location/handling | Safety decision |
|---|---|---|
| User/auth data | Temporary root `qa-runner.json` | Synthetic accounts only; never copy live records. |
| Organization data | Temporary root `.qa-organizations.json` | Synthetic organization/project ownership only. |
| Sessions/cookies | New server process and browser context | Separate from PID 23304; dispose after run. |
| Attachments | Temporary root attachment directory | Small synthetic files only; delete temporary root after shutdown. |
| Screenshots/videos | Temporary target subdirectories | Do not invoke real execution; no real artifacts. |
| Reports/logs | Temporary target directories | Keep bounded and dispose with test root. |
| Imports/shared paths | Do not configure or invoke | Prevent external files, GitHub, ZIP, and path traversal exposure. |
| SMTP/SMS | Inject capturing deliveries | No external delivery or credentials. |
| Database connections | Do not configure or test | No external database access. |
| CI/CD | Do not invoke | No external repository, pipeline, or credential use. |

## Proposed disposable configuration

1. Create a unique temporary directory using the test framework/PowerShell temporary-directory mechanism.
2. Start the supported test constructor with `port=0`, which selects an ephemeral `127.0.0.1` port.
3. Inject bounded in-process email and SMS capture deliveries; do not set SMTP credentials.
4. Use one browser context pointed only at the returned loopback URI.
5. Create synthetic identities through the supported registration/verification flow or existing in-process capture seam.
6. Create `QA_E2E_F12A2_<run-id>` and `QA_E2E_PROJECT_F12A2_<run-id>` only inside this temporary root.
7. Disable external actions by omission: do not invoke runner execution, imports, shared paths, database tests, CI/CD, SMTP, or remote integrations.
8. Shut down the test-owned server, close the browser, and delete only the unique temporary root after assertions and evidence capture.

No persistent environment variables, live configuration, or production credentials are required.

## E2E execution plan

Run sequentially with one worker and bounded timeouts:

1. Verify synthetic account login, session creation, logout, and CSRF handling.
2. Create the synthetic organization and verify owner membership and organization listing.
3. Create the synthetic project through the organization route and verify ownership.
4. Refresh and verify organization/project persistence and selection.
5. Create a sprint and assign a synthetic story.
6. Exercise User Story CRUD, comments, status persistence, and archive cleanup.
7. Exercise Test Case CRUD and story linking.
8. Exercise Defect creation/link visibility using the existing defect store.
9. Verify project/organization isolation with authorized synthetic identities.
10. Verify unauthorized, cross-project, cross-tenant, CSRF, and suspended-membership denials.
11. Archive the synthetic project where supported.
12. Shut down and dispose only the temporary root; confirm the live PID and port were never targeted.

No mutation step is authorized yet. A separate explicit approval is required before launch and data creation.

## Resource safety plan

- One test server, one browser worker, one Maven process at most.
- Port 0 only; never 8081.
- No full Maven suite, Docker, PostgreSQL, browser parallelism, or load testing.
- Small deterministic fixtures and bounded request/test timeouts.
- No background polling or unbounded retries.
- Cleanup in a finally/afterEach path with ownership checks against the unique temporary root.

## Risks and gates

- The current account has no active organization/project scope, so current-store mutations remain unsafe.
- Organization deletion is unsupported; isolated storage is required to make cleanup reversible.
- The application still contains external execution/import capabilities; safety depends on not invoking them in this plan.
- The test constructor is package-private and intended for in-process tests; a separate supported test harness may be needed rather than a raw Main launch.

## Launch approval request

Approval is requested for one isolated, ephemeral-port test-server launch using the supported test constructor, temporary storage, synthetic identities, injected in-process delivery, and no external integrations. The live PID 23304 and port 8081 will remain untouched. Until approval is granted, no server or test data will be created.

Production readiness remains **NOT APPROVED**.
