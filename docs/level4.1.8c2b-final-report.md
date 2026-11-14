# Level 4.1.8C-2B — H-005 Next Shared Store Security Fix

Batch C-2B: **PASS for the selected CI/CD result-import family**  
H-005 overall: **OPEN**  
Production readiness: **NOT APPROVED**

## Selected family and root cause

Candidate B was selected: CI/CD execution/result imports and their project-scoped configuration boundary.

The affected endpoint was `POST /api/testcases/sync` at [RunnerServer.java:873](../src/main/java/com/example/unifiedqarunner/RunnerServer.java:873), implemented by [syncTestCases](../src/main/java/com/example/unifiedqarunner/RunnerServer.java:4700). Before this batch, the route was treated as an unowned compatibility route and required the platform Admin shortcut. Its GitHub and auto-sync branches loaded `new CiCdConfig().load()`, which reads the legacy global `ciCd` slot rather than the project-scoped `ciCdConfigurations` store. The local-run branch did not compare the requested project scope with the run’s immutable H-004 project context.

This was a tenant-affecting import boundary with credential-backed provider access and no complete project-bound import contract.

## Modified files

- [RunnerServer.java](../src/main/java/com/example/unifiedqarunner/RunnerServer.java:873) — scoped sync authorization, project-scoped configuration loading, local-run ownership matching, and query-parameter allowlist.
- [index.html](../src/main/resources/static/index.html:9467) — sends selected organization/project scope and blocks unscoped UI sync.
- [TenantRouteIntegrationTest.java](../src/test/java/com/example/unifiedqarunner/TenantRouteIntegrationTest.java:178) — focused import isolation tests.
- [level4.1.8c2b-risk-comparison.md](./level4.1.8c2b-risk-comparison.md)
- [level4.1.8c2b-selected-store-matrix.md](./level4.1.8c2b-selected-store-matrix.md)

## Authorization changes

The route now:

1. Requires same-origin/CSRF validation.
2. Requires an authenticated active user with an allowed CI/CD management role.
3. Requires explicit `organizationId` and `projectId` query scope.
4. Resolves organization/project ownership server-side through the existing tenant resolver.
5. Loads only `ciCdConfigurations[projectId]`.
6. Rejects local result imports whose immutable `TenantJobContext.projectId()` differs from the authorized project.
7. Ignores legacy global `ciCd` data for this tenant-scoped import path.
8. Preserves token redaction and existing C-002 configuration checks.

The existing authorization framework was reused; no parallel authorization system was introduced.

## Before/after behavior

| Request | Before | After |
|---|---|---|
| Authorized project sync | Admin-only compatibility path; global config fallback | Requires project/org scope and CI/CD manage authorization |
| Cross-organization sync | Could reach the compatibility path only through Admin behavior | 403 before import |
| Missing scope | Compatibility behavior | 403 |
| Missing CSRF | Existing mutation protection | 403 |
| Unauthenticated | Existing auth gate | 401 |
| Legacy global config | Could be loaded by provider/auto-sync branch | Not used for scoped import |
| Local run from another project | No explicit run/project comparison | Fails closed |

## Validation

Focused selected-family evidence:

- `pipelineResultImportRequiresScopedCiCdOwnershipAndIgnoresLegacyConfig`: PASS.
- Focused `TenantRouteIntegrationTest`: **8/8 PASS**.
- C-2A `AutomationExecutionManagerTest`: **2/2 PASS**.

The focused import test verifies authorized scoped handling, cross-organization denial, missing-scope denial, missing-CSRF denial, unauthenticated denial, and that a legacy global configuration is not exposed or used as the scoped import configuration.

Sequential bounded validation:

| Validation | Result |
|---|---:|
| Maven compile (`mvn -q -DskipTests compile`) | PASS |
| Selected-family focused tests | 8/8 PASS |
| C-2A automation execution tests | 2/2 PASS |
| H-004 `TenantJobContextTest` | 4/4 PASS |
| H-004 `TenantContextResolverTest` | 6/6 PASS |
| Organization API tests | 3/3 PASS |
| C-001 database connection tests | 3/3 PASS |
| Team authorization tests | 6/6 PASS |
| RunnerServer tests | 3/3 PASS |
| Level 3 authentication regression | 4/4 PASS |
| Two-account isolation regression | 3/3 PASS |
| Project ownership regression | 2/2 PASS |
| Combined targeted regression invocation | **44/44 PASS** |
| `git diff --check` | PASS |

No PostgreSQL, Docker, cloud deployment, browser suite, real provider execution, or port 8081 restart was performed.

## Preserved controls

C-001 database isolation, C-002 scoped CI/CD configuration and redaction, H-001 legacy authorization, H-002 related-link IDOR, H-003 bulk artifact deletion, H-004 immutable async context/revalidation, C-2A automation execution ownership, authentication, CSRF, project RBAC, quarantine behavior, and platform Admin separation remained covered by the targeted regression set.

## Remaining H-005 boundaries

Candidate A remains deferred: shared physical report/screenshot/video/log namespaces, plugin artifact writers, report downloads, symlink/retention behavior, and broader artifact migration. Remaining CI/CD gaps also remain: status-cache durability, callback/webhook authenticity and replay protection, external provider result stores, and distributed execution records. Runner registration, PostgreSQL migration, and cloud deployment were not started.

## Final status

- Batch C-2B: **PASS** for the selected CI/CD result-import boundary.
- H-005 overall: **OPEN**; other shared storage boundaries are not independently verified.
- Next H-005 batch: requires explicit approval.
- Production readiness: **NOT APPROVED**.
