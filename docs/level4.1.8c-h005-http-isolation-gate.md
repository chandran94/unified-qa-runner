# H-005 HTTP Isolation Gate

## Result

**PASS for the selected test/mapping CRUD fixture.**

The test server was constructed with a unique `Files.createTempDirectory` root and `new RunnerServer(root, 0, null, null)`. The server selected an OS-assigned port and was accessed only through `127.0.0.1`. The test-owned `qa-runner.json` was the root used by `TestCaseManager`, `AutomationMappingManager`, organization, project, authentication, team, ownership and session stores.

The fixture created only synthetic users, organizations, projects, test cases, a feature file and ownership records. No external provider was contacted. Server shutdown and cleanup were limited to the temporary root. Port 8081 was not restarted, interrupted or contacted.

## Evidence

The passing test-case CRUD run created OrgA/ProjectA and OrgB/ProjectB records, verified list filtering and direct-object denial, then verified that authorized update/delete and CSRF/unauthenticated denials behaved as expected. `StorageRootInjectionTest` independently proved the two selected managers use an injected file and preserve unrelated JSON sections.

## Scope limitation

Other managers with their own persistence paths were not changed. They were initialized under the same disposable `RunnerServer` project root for this fixture. No claim is made that this proves isolation for unrelated future endpoints.
