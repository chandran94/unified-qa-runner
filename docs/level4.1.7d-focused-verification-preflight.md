# Level 4.1.7D Focused Verification — Preflight

## Scope

Verification-only review of the five reported Team Management controls. No production, test, configuration or dependency files were modified. The live application on port 8081 was not started, stopped, queried or interrupted.

## Worktree baseline

- Branch: `master`
- HEAD: `d9c99e56e4289c31928dad5a071a552bd75555dc`
- The worktree was already dirty with tracked application/test changes and many untracked reports/tests. Those changes were preserved.
- Existing focused test sources were used as found; no test source was changed.

## Candidate tests and isolation gate

| Test class | Isolation evidence | Port behavior | Decision |
|---|---|---|---|
| `TenantContextResolverTest` | Each fixture creates a unique `Files.createTempDirectory` root and a temporary `qa-runner.json`; stores are constructed from that root and cleaned in `finally`/fixture close | No HTTP server | PASS — safe to run |
| `OrganizationApiIntegrationTest` | Creates a unique temp root, uses `new RunnerServer(root, 0, null, null)`, authenticates synthetic users, and recursively cleans only the owned root in `@AfterEach` | `0` requests an OS-assigned ephemeral port; no 8081 reference | PASS — safe to run |
| Browser/UI automation | No existing approved browser harness identified; live app/browser use is prohibited | Not applicable | BLOCKED — source review only |

No candidate test was found to require external services, real credentials, real user data or the project-root `qa-runner.json`. The tests were run sequentially with `MAVEN_OPTS=-Xmx512m -XX:MaxMetaspaceSize=192m`.

## Execution plan and result

1. `mvn -Dtest=TenantContextResolverTest test` — executed; 6/6 passed.
2. `mvn -Dtest=OrganizationApiIntegrationTest test` — executed; 3/3 passed.

The repository has no `mvnw.cmd`; the first wrapper lookup was not a test execution. Installed Maven was then used. No full suite and no `mvn clean` were run.

