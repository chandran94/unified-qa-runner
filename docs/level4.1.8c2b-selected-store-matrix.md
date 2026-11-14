# Level 4.1.8C-2B — Selected CI/CD Store Matrix

## Selected family

CI/CD execution/result import boundary, centered on `POST /api/testcases/sync` and the project-scoped `ciCdConfigurations` store. The route mutates test-case state from local or provider pipeline results.

| Surface | Current source | Ownership source | Required authorization | Before | After |
|---|---|---|---|---|---|
| CI/CD configuration read | `RunnerServer.java:755`, `CiCdConfig.load(projectId)` | Project-to-organization assignment | Active tenant membership, project membership, CI/CD read | Already scoped | Preserved |
| CI/CD configuration write | `RunnerServer.java:760`, `saveCiCdConfig` | Request scope cross-checked against body and project mapping | CSRF, active membership, CI/CD manage role | Already scoped | Preserved |
| Pipeline status | `RunnerServer.java:778`, `getCiCdStatus` | Project-scoped configuration | Active tenant/project access | Already scoped | Preserved |
| Result import route | `RunnerServer.java:873`, `syncTestCases` | **Project scope and project-scoped CI/CD config** | CSRF, active identity, active membership, CI/CD manage role, project/org match | Admin-only compatibility gate; legacy global `ciCd` load; no project-bound import | Scoped to authorized project; legacy global config ignored |
| Local run result import | `syncTestCases` local branch | Immutable `TenantJobContext.projectId()` | Import project must equal run project; CI/CD manage authorization | Run ID branch did not verify requested tenant/project | Mismatch fails closed |
| GitHub/remote result import | `syncTestCases` provider branch | `ciCdConfigurations[projectId]` | Authorized project plus scoped credential | `new CiCdConfig().load()` could load global legacy config | Only selected project configuration is loaded |
| Auto-sync | `syncTestCases` no-run branch | `ciCdConfigurations[projectId]` | Same as provider import | Global config | Project-scoped config |

## Implemented controls

- `/api/testcases/sync` now requires same-origin validation and existing CI/CD manage authorization.
- The route requires explicit `organizationId` and `projectId` query scope through `requireCiCdProjectAccess`.
- The route is removed from the unowned-resource Admin-only compatibility list.
- GitHub and auto-sync branches load only `ciCdConfig.load(projectId)`.
- Local run imports require the run’s immutable H-004 project context to equal the authorized project.
- The frontend now sends the selected organization/project and refuses to start without both.
- The query-parameter allowlist explicitly includes the sync route.
- No token is returned in the response; existing CI/CD redaction is preserved.

## Fail-closed and legacy behavior

Missing scope, invalid scope, unauthenticated requests, cross-organization access, missing CSRF, insufficient role, and project/run mismatch are denied. A legacy global `ciCd` record is not used for tenant-scoped imports. It is not migrated or reassigned by this batch.

## Deferred

Shared artifact/report physical namespaces, pipeline status cache durability, webhook authenticity/replay controls, runner registration, external callback stores, and PostgreSQL remain outside this batch.
