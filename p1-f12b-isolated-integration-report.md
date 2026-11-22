# Unified QA Runner — P1-F12B Isolated Integration E2E Report

## Execution status

**Completed with evidence.** The live application was preserved. All executed application servers used temporary roots, ephemeral ports, loopback binding, and in-process delivery capture.

## Isolation preflight

- Live PID 23304 remained the expected Unified QA Runner Java process.
- Live port 8081 remained owned by PID 23304 and returned HTTP 200 before and after testing.
- Test roots were created by `Files.createTempDirectory` under the system temporary directory and were not nested in the live storage root.
- The test constructor used `RunnerServer(root, 0, ...)`; port 0 selected an ephemeral loopback port.
- Synthetic accounts and organizations were created only in temporary roots.
- Injected verification delivery prevented SMTP/email delivery.
- No test configured imports, shared paths, databases, CI/CD, runner execution, or external integrations.
- No live browser cookies or sessions were reused; Selenium browsers targeted the isolated server URL.

## Exact command

```powershell
$env:MAVEN_OPTS='-Xmx512m -XX:MaxMetaspaceSize=192m'
mvn -q '-Dtest=OrganizationBrowserE2ETest,OrganizationApiIntegrationTest,UserStoryHttpSecurityTest,UserStoryStoreTest,LiveTwoAccountAuthorizationTest,TenantRouteIntegrationTest,CrossTenantHttpSecurityVerificationTest' test
```

One Maven process ran with the bounded heap setting. No `mvn clean` or full suite was run.

## Test results

| Test class | Pass | Fail | Error | Skipped | Evidence scope |
|---|---:|---:|---:|---:|---|
| OrganizationBrowserE2ETest | 4 | 0 | 0 | 0 | Real Selenium UI organization management, switching, invitations, and dashboard relocation. |
| OrganizationApiIntegrationTest | 3 | 0 | 0 | 0 | Organization API, membership, invitations, and project ownership integration. |
| UserStoryHttpSecurityTest | 5 | 0 | 0 | 0 | Story links, comments, attachments, defects, Kanban, reload, and cross-tenant denial. |
| UserStoryStoreTest | 11 | 0 | 0 | 0 | Isolated persistence, sprint lifecycle, story links, board status, and comments. |
| TenantRouteIntegrationTest | 8 | 0 | 0 | 0 | Tenant route and authorization isolation. |
| CrossTenantHttpSecurityVerificationTest | 3 | 0 | 0 | 0 | Cross-tenant HTTP security. |
| LiveTwoAccountAuthorizationTest | 0 | 0 | 0 | 1 | Skipped because `qa.runner.liveBrowser` was not set; live browser mode was not enabled. |
| **Total** | **34** | **0** | **0** | **1** | **35 unique tests** |

## Functional evidence

- Organization creation, membership management, invitation flow, organization switching, and Settings relocation: **PASS** through isolated Selenium tests.
- Project organization ownership and visibility: **PASS** through isolated API/browser coverage.
- Sprint creation, assignment validation, lifecycle, and persistence: **PASS** through isolated store/security coverage.
- User Story CRUD/persistence, comments, links, attachments, defects, Kanban status, refresh persistence, and cross-tenant denial: **PASS** through focused isolated tests.
- Test Case linking and project/tenant filtering: **PASS** through `UserStoryHttpSecurityTest` and isolated store coverage.
- Defect linking and reload persistence: **PASS** through `UserStoryHttpSecurityTest`.
- Browser drag/drop failure rollback and complete browser CRUD across every module: **NOT VERIFIED**; the executed browser class focuses on organization workflows, while User Story integration was covered through isolated HTTP/store tests.

## Security evidence

Focused tests passed for authentication/session use, CSRF-bearing mutations, project ownership, tenant filtering, cross-tenant denial, comment ownership, attachment authorization, defect links, and Kanban cross-tenant mutation denial. The skipped live-browser authorization class is not counted as a pass.

## Cleanup evidence

The Maven test reports show successful teardown, and no matching `qa-org-browser-*`, `qa-org-api-*`, or `qa-user-story-http-*` temporary roots remained after execution. The live PID and port remained healthy. No shared storage cleanup was attempted.

## Observations and limitations

- Selenium logged a CDP-version compatibility warning for Chrome 157 versus the available Selenium 4.27 DevTools modules; tests still passed. This is a tooling warning, not a reproduced application defect.
- The application emitted a bounded `204` response-header warning and `Runner request failed: stream closed` during test execution; no targeted test failed and no data-integrity impact was established. Treat as a follow-up observation, not a confirmed defect.
- One live-browser authorization test was skipped by its explicit system-property gate.
- No production source fix was made. Any follow-up fix requires separate P1-F12C authorization.

## Final verdict

The isolated organization/project/user-story integration evidence is **PASS** for the executed scenarios, with one explicitly skipped live-browser scenario and remaining browser coverage gaps. Production readiness remains **NOT APPROVED**.
