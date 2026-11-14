# Level 4.1.8C-2E — Integration and Shared-Storage Ownership Matrix

## Scope and evidence

This matrix is based on the current backend source, prior C-001 through C-2D reports, and the focused tests run for this batch. Frontend labels and documentation were not treated as implemented integrations.

| Family | Actual implementation and routes | Store / physical storage | Ownership and authorization | Current result / risk |
|---|---|---|---|---|
| Remote imports (selected) | `GET /api/imports`; `POST /api/imports/github`; `POST /api/imports/zip`; `POST /api/imports/{id}/scan`; `DELETE /api/imports/{id}` in `RunnerServer` lines 644–680 | `RemoteImportConfig`; `qa-runner.json` `imports[]`; shared `<projectRoot>/imports` directory | Project ownership is in `ProjectOwnershipManager` as `REMOTE_IMPORT`; create requires project test-execution access; scan/delete use owned project access; legacy imports are unresolved and fail closed for ordinary users | Before this batch, `/api/catalog` serialized every import including path/source metadata, and stored paths were not uniformly constrained before scan/delete/execution. HIGH exposure. Fixed in this batch. |
| CI/CD configuration and result import | `/api/cicd/config`, `/api/cicd/test-connection`, `/api/cicd/generate`, `/api/cicd/status`, `/api/cicd/commit`, `/api/testcases/sync` | `CiCdConfig`; `qa-runner.json` scoped `ciCdConfigurations` | Organization/project context and project `ci-cd` permissions; token redaction; C-002/C-2B reports and targeted tests | Existing protection retained. External URL validation/egress hardening is not expanded in C-2E. |
| Database connections | `/api/database/connections*`, connection tests, connect/disconnect | `DatabaseConnectionRepository` and protected credential fields in `qa-runner.json` | Organization ownership, active membership, database permissions, encrypted/redacted secrets | C-001 verified in prior work; not modified. |
| Shared paths | `/api/shared-paths*`, catalog discovery | `SharedPathConfig`; configured filesystem paths | Legacy unresolved paths are denied to ordinary project users; platform-admin path remains separate | Deferred. It is a separate shared-storage family and was not changed in this batch. |
| Reports and report files | Report APIs and `/reports/*` serving | Report metadata and `target/runner-reports` | Existing project ownership and C-2C trusted-root serving | C-2C protection retained; not modified. |
| Screenshots/videos | `/screenshots/*`, `/videos/*`, bulk deletion routes | Local artifact directories and metadata | H-003 ownership and tenant filtering | H-003 retained; not modified. |
| Automation execution results | `/api/automation-executions*` | `AutomationExecutionManager` in `qa-runner.json` | Explicit organization/project fields plus ownership index and tenant checks | C-2A retained; not modified. |
| Runner registration / remote callbacks | No remote runner registration, token, heartbeat, job claim, result callback, or remote-runner store found | None | Not applicable; local in-process executor only | C-2D audit-only result retained. |
| Webhooks / provider callbacks | No webhook or callback route found in the backend route inventory | None | Not applicable | No webhook tests or remediation claimed. |
| Jira, Azure DevOps, AWS, Snowflake, Slack, Teams | Names occur in documentation/frontend content only; no corresponding backend store/route was found in this scope | None confirmed | Not applicable | Not counted as implemented vulnerabilities. |

## Remote-import ownership details

- Authoritative project owner: `ProjectOwnershipManager.projectId("REMOTE_IMPORT", id)`.
- Authoritative organization owner: `OrganizationStore.findProjectOwnership(projectId)` through the existing tenant resolver path.
- Required user state: authenticated active session, active project membership, and the existing `TEST_EXECUTION` permission.
- Legacy behavior: `ProjectOwnershipManager.migrate` records imports without a trustworthy relationship as `UNRESOLVED`; ordinary users cannot read, scan, execute from, or delete them.
- Physical path behavior after this batch: `RemoteImportConfig.resolveImportDirectory` requires a real directory below the real `<projectRoot>/imports` root. Missing, outside-root, or unsafe entries are skipped or return a generic 404.
- API behavior after this batch: import lists/catalogs are filtered before serialization and do not expose local filesystem paths.

## Test coverage

The new `RemoteImportTenantIsolationTest` verifies A/B catalog and list filtering, path redaction, and trusted-root rejection. Existing `TenantRouteIntegrationTest`, `TenantContextResolverTest`, `TenantJobContextTest`, `AutomationExecutionManagerTest`, `ReportStorageSecurityTest`, `RunnerReportTest`, `ProjectOwnershipManagerTest`, `TeamAuthorizationTest`, and `AuthenticationFoundationTest` were rerun as targeted regressions.

