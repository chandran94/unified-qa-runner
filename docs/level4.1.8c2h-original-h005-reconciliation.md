# Level 4.1.8C-2H — Original H-005 Reconciliation

## Evidence basis

The immutable original evidence is in [level4.1.8a-final-report.md](./level4.1.8a/level4.1.8a-final-report.md), [level4.1.8a-execution-security-audit.md](./level4.1.8a/level4.1.8a-execution-security-audit.md), [level4.1.8a-resource-ownership-matrix.md](./level4.1.8a/level4.1.8a-resource-ownership-matrix.md), and [level4.1.8a-remediation-roadmap.md](./level4.1.8a/level4.1.8a-remediation-roadmap.md). The original H-005 reference is the report's High finding **H-005: several integration and execution stores are process/project-root scoped rather than tenant-scoped**. Its named families were `UserStoryStore`, `TestCaseManager`, `AutomationMappingManager`, `AutomationExecutionManager`, `SharedPathConfig`, `RemoteImportConfig`, `WikiManager`, and shared artifact directories, with related CI/CD, pipeline-run, credential and runner boundaries.

Current source and tests were checked against the recovered reports and the C-2A through C-2G reports. Historical line numbers are not treated as current source locations.

## Original-to-current matrix

| Original H-005 family/reference | Current routes and stores | Current ownership/authentication/RBAC evidence | Prior fix and tests | Current status, severity, next action |
|---|---|---|---|---|
| `AutomationExecutionManager` and automation executions | `GET /api/automation-executions`; `AutomationExecutionManager` persisted records | `RunnerServer` filters by stored `organizationId`/`projectId`, validates project assignment and `TEST_EXECUTION` through `TenantContextResolver`; unassigned records are excluded except platform admin | C-2A; `AutomationExecutionManagerTest`, targeted tenant group | **VERIFIED REMEDIATED**, Medium; retain regression |
| CI/CD result import and test-case sync | `POST /api/testcases/sync`; `CiCdConfig`, `PipelineStatusService`, `TestCaseManager` | Route now resolves project through `requireCiCdManage`; config loads by project; local run requires matching `TenantJobContext.projectId`; auto-sync uses project-scoped status cache | C-2B and C-2G; tenant route and pipeline scope tests | **VERIFIED REMEDIATED** for identified boundary, Medium; retain regression |
| CI/CD pipeline status/cache | `GET /api/cicd/status`; `PipelineStatusService.cache` | Project authorization occurs before status; current cache key includes server-resolved project ID; unscoped compatibility calls do not read/write cache | C-2G; `PipelineStatusServiceTest` 1/1 plus 40-test targeted set | **VERIFIED REMEDIATED**, Medium; no further C-2H change |
| Shared execution runs and result metadata | `POST/GET /api/runs`, `/api/runs/{id}`, `/api/runs/{id}/report`; in-memory `RunEntry`, `TenantJobContext` | Creation carries immutable organization/project/user context; retrieval and cancellation use project ownership; report serving checks run access and trusted report root | H-004/C-1 and C-2C; `TenantJobContextTest`, `RunnerReportTest`, targeted route tests | **PARTIALLY REMEDIATED**, High residual durability/testing limitation; C-3 durable/restart audit |
| Screenshots and videos | `/api/screenshots`, `/api/videos`, `/screenshots/*`, `/videos/*`; `target/screenshots` and `target/videos` | Individual/list/bulk operations filter through artifact ownership; path traversal rejected; ownership is recorded for run artifacts; unassigned files fail closed for ordinary users | H-003; artifact paths exercised in targeted regressions | **VERIFIED REMEDIATED** for current access boundary, Medium; broader filesystem namespace audit deferred |
| Reports and report exports | `/api/runs/{id}/report`; `ReportWriter`, `ReportStorageSecurity` | Run ownership required before report route; canonical real-path trusted-root and regular-file validation; bounded streaming and generic denial | C-2C; `ReportStorageSecurityTest` 3/3 and `RunnerReportTest` | **VERIFIED REMEDIATED** for report file serving, High historical; export matrix still requires separate audit |
| Shared paths and catalog assets | `/api/shared-paths`, `/api/shared-paths/{name}/scan`, `/api/catalog`; `SharedPathConfig` | Ownership records are checked with `TEST_EXECUTION`; catalog serializes only owned paths/imports; unsafe legacy paths are skipped | C-2F; `SharedPathTenantIsolationTest` 1/1 and combined regressions | **VERIFIED REMEDIATED** for catalog filtering, Medium; preserve trusted-root tests |
| Remote imports and imported assets | `/api/imports`, `/api/imports/github`, `/api/imports/zip`, scan/delete; `RemoteImportConfig` | Project ownership required; local paths omitted from responses; trusted real-root resolution and sanitized server-generated ZIP temp path | C-2E; `RemoteImportTenantIsolationTest` 2/2 | **VERIFIED REMEDIATED**, High historical; retain import tests |
| Test cases, mappings and discovered assets | `/api/testcases`, `/api/testcase-automation-mappings`, catalog/execution paths; `TestCaseManager`, `AutomationMappingManager`, plugin filesystem discovery | Route/list filters and mapping ownership checks exist; unresolved legacy ownership is not assigned; plugin discovery is only exposed through authorized catalog/execution paths | H-001/H-002 and C-2F; tenant route/project ownership tests | **PARTIALLY REMEDIATED**, Medium; dedicated CRUD and asset namespace matrix required |
| User stories, criteria, sprints, boards and attachments | `/api/user-stories/*`, attachment routes; `UserStoryStore` | Parent story/epic/sprint/project checks use project ownership and domain RBAC; attachment download/delete uses `ATTACHMENT` ownership; shared JSON store remains compatibility storage | H-001/H-002; organization/project tests, but no complete H-005 matrix | **PARTIALLY REMEDIATED**, Medium/High; 4.1.8D ownership audit |
| Wiki | `/api/wiki/*`; `WikiManager` | Authenticated routes and parent checks are present in current server; full tenant/project list and legacy quarantine evidence is incomplete | No dedicated H-005 batch | **UNVERIFIED**, High; do not patch without a complete route/store contract |
| Logs and execution history | Run response/log buffers and plugin report files; primarily `RunEntry` and run directories | Run-level access is checked; persistence, retention, redaction and restart ownership are not independently verified across all plugin outputs | H-004/C-2C partial | **PARTIALLY REMEDIATED**, High; C-3/D dedicated audit |
| Database connections and connection tests | `/api/database/connections*`, `/api/database/connection-tests/*`; `DatabaseConnectionRepository` | Current project/organization ownership and permission checks precede list/object/mutation/test; secret responses are redacted | C-001; database-focused targeted regressions in prior reports | **VERIFIED REMEDIATED**, Critical historical; retain regression |
| Integration configurations/credentials beyond CI/CD/database | No confirmed provider-specific persistent route/store found in current source inventory | Names in documentation/frontend do not establish an implementation; no live webhook/callback store found | C-2E covers remote import configuration only | **NOT APPLICABLE** for unimplemented providers; re-audit if introduced |
| Pipeline run records from external providers | Provider responses are transient in `PipelineStatusService`; no durable shared run-record repository found | Current status cache is project-scoped; provider callbacks/webhooks are absent | C-2G; `PipelineStatusServiceTest` | **VERIFIED REMEDIATED** for current cache boundary, Medium; durable history remains out of scope |
| Runner registration/configuration and remote claims | No registration, token, heartbeat, claim or result-submission route/store | Local in-process executor only; no remote runner resource exists | C-2D audit-only | **NOT APPLICABLE**, Low; reassess only if architecture changes |

## End-to-end boundary findings

- API authentication/session and same-origin/CSRF gates are retained in `RunnerServer`.
- Project ownership is resolved from `OrganizationStore`/`ProjectOwnershipManager`; client `organizationId` is checked, not trusted as ownership proof.
- Tenant filtering is server-side for runs, automation executions, shared paths, imports, catalog entries, test cases and artifacts.
- Background local runs carry `TenantJobContext` and revalidate before sensitive execution/result/artifact boundaries.
- Legacy/unassigned resources are denied or filtered for ordinary users rather than silently assigned.
- No remote worker, webhook, SSE or WebSocket resource boundary was found.

## Residual exposure and closure prerequisites

H-005 cannot be closed from this reconciliation. The remaining gaps are evidence and architecture boundaries rather than a confirmed independently fixable C-2H vulnerability:

1. Complete tenant CRUD and legacy-quarantine verification for wiki, attachments, stories and other JSON-backed families.
2. Verify durable ownership/retention/redaction for logs, plugin results, exports and restart behavior.
3. Establish a durable execution/history ownership design before claiming crash recovery or distributed-worker isolation.
4. Reassess integration/webhook boundaries when those features actually exist.

These prerequisites are deferred and no application code was changed by C-2H.
