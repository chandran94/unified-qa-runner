# P1-F12J Project Restore UI, RBAC and Performance Report

## Scope and safety

This phase used disposable storage, an in-process `RunnerServer(..., 0, ...)`, a dynamically assigned loopback port, synthetic identities, and one headless browser worker. PID 23304 and `127.0.0.1:8081` were not stopped, restarted, or accessed for test mutations. No commit, push, deployment, external integration, or real email was performed.

## Root cause and implementation

The project archive/restore API already existed and was Admin-only, but the User Story project UI had no archived-project filter or restore action. The static frontend also loaded only the default project listing and did not explicitly scope that request to the selected organization. These gaps prevented safe discovery of archived projects and could leave the UI without a valid active project context.

Changes were limited to [index.html](./src/main/resources/static/index.html):

- Load projects with `includeArchived=true` and the selected `organizationId`.
- Keep archived projects out of active project fallback and board context.
- Add Active, Archived, and All project filters, defaulting to Active.
- Add Admin-only Archive and Restore controls using the existing styled modal pattern.
- Disable the lifecycle action while saving and reload the project list after success.
- Query team permissions with a project scope and expose management controls only for the server-reported Admin role.

The isolated browser coverage was added in [P1F12JProjectRestoreBrowserE2ETest.java](./src/test/java/com/example/unifiedqarunner/P1F12JProjectRestoreBrowserE2ETest.java).

## Results

| Scenario | Result | Evidence |
|---|---|---|
| Admin archive and restore through UI | PASS | P1F12J test 1/1; status returned to Active after reload |
| Archived status filter | PASS | Archived project was listed after scoped reload |
| Status persistence after refresh | PASS | Restored project remained active after browser refresh |
| Viewer archive/restore visibility and denial | PASS | Controls hidden; archive mutation denied with HTTP 403 |
| Project Manager policy | PASS | Server permission snapshot allows story/test-case writes per existing policy |
| Product Owner policy | PASS | Server permission snapshot allows story writes but not test-case writes |
| Cross-tenant non-disclosure | PASS | Covered by prior P1-F12I isolated test, 2/2 |
| Linked story/sprint preservation | PASS | Covered by prior P1-F12H isolated test, 2/2 |

Focused results:

- `P1F12JProjectRestoreBrowserE2ETest`: 2 tests, 2 passed, 0 failed, 0 errors, 0 skipped.
- `P1F12IRbacOrganizationBrowserE2ETest`: 2/2 passed.
- `UserStoryHttpSecurityTest`: 5/5 passed.
- `P1F12EBrowserE2ETest`: 6/6 passed after a test-only readiness wait was added.
- `mvn -q -DskipTests compile`: exit code 0.
- `git diff --check`: exit code 0; only pre-existing line-ending warnings were reported.

## Performance and limitations

This phase did not run load or stress testing. The P1-F12J test did not collect production performance benchmarks. Prior isolated P1-F12H observations recorded archive/restore API timings of 32 ms and 27 ms in one run; those are observations, not targets. Full PM/PO CRUD coverage across every module, live-browser verification of the new UI, and production-environment performance remain unverified.

## Decision

The isolated project restore UI and source-derived PM/PO policy checks are verified for the covered scenarios. The live PID was not restarted, so the running build does not contain these source changes. A separately approved controlled restart is required for live activation. Production readiness remains **NOT APPROVED**. H-005 remains **CLOSED**; D1-F-001 and D1-F-002 remain **OPEN**.
