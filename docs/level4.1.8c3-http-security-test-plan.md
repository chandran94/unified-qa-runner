# Level 4.1.8C-3 — HTTP Security Verification Test Plan

## Scope

Verification only. No production authorization, persistence or schema code is changed by C-3.

## Isolation strategy

- Each test creates a unique `Files.createTempDirectory` root.
- The root receives a disposable `qa-runner.json`; all `RunnerServer` stores are constructed with that root.
- `RunnerServer(root, 0, ...)` uses an OS-assigned port and binds the test server to `127.0.0.1`.
- Test users, organizations, projects, ownership records, artifacts and configuration are synthetic.
- Each user receives a separate cookie and CSRF token through `/api/auth/login`.
- Tests use bounded `HttpClient` connection timeouts and close the server in `@AfterEach`.
- Teardown recursively removes only the test-owned temporary root.

The live application and port 8081 are not used or interrupted. No external provider, database, browser, PostgreSQL, cloud or real test execution is invoked.

## Fixture model

Organization A and Organization B each have one project, one disposable authenticated member and separate project ownership records. C-3 fixtures cover shared paths, automation executions, CI/CD settings, synthetic imports, and database configuration metadata. Unassigned records remain unowned and are not auto-assigned.

## Authentication and request controls

Login is performed through the production `/api/auth/login` route. Protected mutation requests use the CSRF token returned by login; negative cases omit it. Cross-tenant tests retain the authenticated cookie but forge the organization/project scope, exercising the real server-side resolver.

## Verification classes

- [CrossTenantHttpSecurityVerificationTest.java](../src/test/java/com/example/unifiedqarunner/CrossTenantHttpSecurityVerificationTest.java): 3 HTTP tests for catalog/execution filtering, project-scoped CI/CD status, unauthenticated access and CSRF.
- [TenantRouteIntegrationTest.java](../src/test/java/com/example/unifiedqarunner/TenantRouteIntegrationTest.java): 8 HTTP tests for C-001/C-002, H-001/H-002/H-003, CI/CD import, automation execution, project routes and CSRF.
- [SharedPathTenantIsolationTest.java](../src/test/java/com/example/unifiedqarunner/SharedPathTenantIsolationTest.java): 1 HTTP catalog test.
- [RemoteImportTenantIsolationTest.java](../src/test/java/com/example/unifiedqarunner/RemoteImportTenantIsolationTest.java): 1 HTTP catalog/import test plus 1 trusted-root unit test.

## Execution order

1. Verify git status and test-root isolation.
2. Compile without tests.
3. Run the new HTTP class.
4. Run existing HTTP tenant classes.
5. Run non-overlapping context, ownership, report, authentication and RunnerServer regressions.
6. Run `git diff --check`.

## Stop and finding policy

A reproducible authorization failure would be recorded as a C-3 finding without changing production code. Tests are not allowed to connect to live providers or modify existing data. Coverage gaps are reported as BLOCKED/NOT RUN and are not converted into PASS.
