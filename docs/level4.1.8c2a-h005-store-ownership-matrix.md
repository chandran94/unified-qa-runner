# Level 4.1.8C-2A — H-005 Shared Store Ownership Matrix

Audit basis: source inspection plus the Level 4.1.8A–C-1 reports. This is a bounded inventory of the execution/integration storage surfaces actually present in the project. No external provider or real execution was invoked.

## Findings and ranking

| Rank | Resource family | Store / physical location | Routes and write paths | Current authoritative ownership | Current exposure | Risk / batch |
|---:|---|---|---|---|---|---|
| 1 | Automation execution/result records | `AutomationExecutionManager`, `qa-runner.json`, `automationExecutions` | GET `/api/automation-executions` at [`RunnerServer.java:567`](../src/main/java/com/example/unifiedqarunner/RunnerServer.java:567); async write at [`RunnerServer.java:2225`](../src/main/java/com/example/unifiedqarunner/RunnerServer.java:2225) | Derived from test-case/project ownership; record itself has no explicit org/project owner | Result records are saved before the ownership index is assigned; malformed/legacy records rely on related test-case filtering | **HIGH — selected Batch 1** |
| 2 | Shared reports, screenshots and videos | `target/runner-reports`, `target/screenshots`, `target/videos`; ownership index | Run/report and artifact routes; writes from plugins and [`recordRunArtifacts`](../src/main/java/com/example/unifiedqarunner/RunnerServer.java:2940) | `TEST_RUN` or artifact ownership index | Physical namespace is shared; metadata access is currently filtered, but guessing/storage collisions and legacy metadata remain | **HIGH — deferred** |
| 3 | CI/CD configuration and pipeline status | `CiCdConfig`, `qa-runner.json`, `ciCdConfigurations`; `PipelineStatusService` cache | `/api/cicd/*` at [`RunnerServer.java:755`](../src/main/java/com/example/unifiedqarunner/RunnerServer.java:755) | Project-scoped configuration plus existing C-002 route checks; legacy `ciCd` singleton remains | Token-bearing legacy singleton and process cache are not a full tenant store | **HIGH — C-002 regression/deferred architecture** |
| 4 | Database connection metadata and async tests | `DatabaseConnectionRepository`, `qa-runner.json`, `ConnectionTestService` | `/api/database/*` at [`RunnerServer.java:389`](../src/main/java/com/example/unifiedqarunner/RunnerServer.java:389) | Explicit organization ID and C-001 service checks | Password/username encrypted and response-redacted; async test state is process-local | **MEDIUM — C-001 regression** |
| 5 | Test run history | `RunnerServer.RunEntry`, in-memory `runs` map | `/api/runs*` and async queue | H-004 `TenantJobContext` plus `TEST_RUN` ownership index | No restart durability; shared report output remains physical H-005 scope | **HIGH — H-004 pass, artifact boundary deferred** |
| 6 | Automation mappings | `AutomationMappingManager`, `qa-runner.json` | GET/POST/DELETE `/api/testcase-automation-mappings` at [`RunnerServer.java:520`](../src/main/java/com/example/unifiedqarunner/RunnerServer.java:520) | Test-case project ownership and `AUTOMATION_MAPPING` index | Record has no org field but route filters/assigns through authoritative test case | **MEDIUM — deferred with broader legacy stores** |
| 7 | Test cases, suites and user stories | `TestCaseManager`, `UserStoryStore`, `qa-runner.json` | Many compatibility routes | Project relationship and ownership index where migrated | Many legacy records lack intrinsic owner; quarantine/route-specific behavior | **HIGH — core CRUD batch deferred** |
| 8 | Shared paths and remote imports | `SharedPathConfig`, `RemoteImportConfig`, `qa-runner.json` and configured filesystem paths | `/api/shared-paths`, `/api/imports`; consumed by run discovery | Ownership index by configured ID/name where present | Underlying directories are shared and path policy is not a tenant namespace | **HIGH — execution/input isolation deferred** |
| 9 | Integration connection metadata | Provider-specific configuration and CI/CD settings | CI/CD connection/test routes | Existing project authorization; provider credentials are encrypted/redacted where implemented | Provider credentials and external side effects need full per-tenant store review | **HIGH — deferred** |
| 10 | Runner registration/configuration | No runner registration or claim store found | No runner registration/claim endpoint found | Not implemented | No current cross-tenant claim path; future boundary unverified | **NOT IMPLEMENTED — future phase** |
| 11 | CI/CD run records/results | `PipelineStatusService` in-memory cache and provider response | `/api/cicd/status`; provider polling | Project selected before status lookup | Cache is process-wide and keyed by provider/repository result, not a durable tenant record | **HIGH — deferred** |
| 12 | API/Selenium/Newman/Cucumber result records | Plugin result objects and shared report directories | Plugin execution through `ExecutionService` | H-004 run context and `TEST_RUN` metadata | Physical reports are shared; no separate per-family durable result store | **HIGH — deferred with artifacts** |
| 13 | Background execution records | `AutomationExecutionManager` plus `RunnerServer` completion path | `persistAutomationExecution` | H-004 context currently revalidated; stored result lacks explicit owner | Selected slice fixes stored ownership metadata and access filter | **HIGH — selected Batch 1** |
| 14 | Audit logs, notifications and AI results | No tenant-specific durable store found in inspected execution path | AI/ticket routes exist; no async tenant result store found | Not established in this scope | Not verified as a shared execution store | **MEDIUM/UNVERIFIED — deferred** |

## Selected Batch 1

The selected family is **automation execution/result records and their background write path**. They are one closely related boundary: the record is created by the H-004 worker and read by `/api/automation-executions`. The change can be independently verified without migrating the shared execution engine, artifacts, CI/CD, or PostgreSQL.

Required ownership contract:

- worker supplies organization/project from immutable `TenantJobContext`;
- record stores those values explicitly;
- server rejects missing or conflicting ownership before persistence;
- `AUTOMATION_EXECUTION` ownership index is assigned only to the same authoritative project;
- list filtering uses the execution ownership index before serialization;
- legacy records without trustworthy ownership remain inaccessible/quarantined;
- test-case and asset filters are retained as narrowing filters, never as the ownership source;
- no token, password or raw secret is stored in execution metadata.

## Deferred boundaries

This batch does not migrate `qa-runner.json`, shared report/artifact directories, CI/CD configuration/status, database connections, test-case/story stores, shared paths/imports, runner registration, provider caches, or PostgreSQL. Existing C-001/C-002/H-001/H-002/H-003/H-004 controls are regression scope only.

## Legacy handling

The current `ProjectOwnershipManager.migrate` can derive an execution index only when a test-case-to-project relationship is authoritative. That compatibility behavior is retained. A result record without explicit stored ownership or a trustworthy relationship is not assigned to the current tenant; it is excluded from tenant lists and remains quarantined/admin-review data.
