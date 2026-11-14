# Level 4.1.8C-2G — H-005 Audit Evidence Recovery and Residual Isolation

## Status

**Batch C-2G: PASS for the selected one-family remediation.**  
**H-005 overall: OPEN.**  
**Production readiness: NOT APPROVED.**

This report does not close H-005. Several broader resource families remain partially remediated or unverified and require separately approved work.

## Evidence recovery

The original Level 4.1.8A reports were recovered under [docs/level4.1.8a/](./level4.1.8a/). The previous C-2F “unavailable” statement resulted from checking only the top-level docs directory and is superseded. The recovery details and search scope are in [level4.1.8c2g-audit-evidence-recovery.md](./level4.1.8c2g-audit-evidence-recovery.md).

The recovered audit identified the original shared execution/integration scope and historical C-001/C-002/H-001–H-005 findings. Current status was reconstructed from source and tests rather than copied from historical PASS claims.

## Selected family and root cause

Selected family: **project-scoped CI/CD pipeline status and auto-sync cache**.

Affected paths:

- `GET /api/cicd/status` → `RunnerServer.getCiCdStatus(String)` → `PipelineStatusService.getLatestStatus(...)`.
- `POST /api/testcases/sync` auto-sync branch → `PipelineStatusService.getRuns(...)`.

Before the fix, `PipelineStatusService` used `platform + repository/Jenkins URL` as the cache key. The route authorization correctly established a project, but that authoritative scope was discarded before caching. Projects sharing a provider URL could therefore receive another project's cached run metadata. This was a cross-project/shared-store confidentiality boundary; it did not require assuming a provider vulnerability or exposing credentials.

## Modified files

- [PipelineStatusService.java](../src/main/java/com/example/unifiedqarunner/PipelineStatusService.java): added project-scoped status/run overloads, included authoritative project ID in cache keys, and disabled cache reads/writes for unscoped compatibility calls.
- [RunnerServer.java](../src/main/java/com/example/unifiedqarunner/RunnerServer.java): passed the server-resolved project ID to status and auto-sync cache calls.
- [PipelineStatusServiceTest.java](../src/test/java/com/example/unifiedqarunner/PipelineStatusServiceTest.java): verifies identical provider settings produce distinct cache scopes for different projects.

No credentials, live data, storage migration, RBAC policy, external integration, queue, or prior tenant-isolation implementation was changed.

## Before/after behavior

| Case | Before | After |
|---|---|---|
| Two projects use the same repository URL | Shared cache key could reuse run history | Cache keys contain each authoritative project ID |
| `/api/cicd/status` | Project authorization occurred, then scope was lost | Project authorization and project cache scope are preserved |
| Auto-sync | Used unscoped `getRuns(settings)` | Uses `getRuns(settings, projectId)` |
| Direct unscoped service use | Could populate shared cache | Does not read or populate the cache |
| Secret handling | Existing encrypted config/token redaction path | Unchanged |

## Validation

Commands run sequentially:

1. `mvn -q -DskipTests compile` — **PASS**.
2. `mvn -q -Dtest=PipelineStatusServiceTest test` — **PASS, 1/1**.
3. Targeted regression command:
   `mvn -q "-Dtest=PipelineStatusServiceTest,SharedPathTenantIsolationTest,RemoteImportTenantIsolationTest,TenantRouteIntegrationTest,TenantContextResolverTest,TenantJobContextTest,AutomationExecutionManagerTest,ReportStorageSecurityTest,RunnerReportTest,ProjectOwnershipManagerTest,TeamAuthorizationTest,AuthenticationFoundationTest" test`
   — **PASS, 40/40; 0 failures; 0 errors; 0 skipped**.
4. `git diff --check` — **PASS**. Git emitted only the existing LF/CRLF warning for `TenantRouteIntegrationTest`; no whitespace errors were reported.

The targeted set covers the new cache-scope property plus tenant context, async job context, project ownership/RBAC, authentication, route isolation, C-2A execution ownership, C-2C report serving, C-2E remote import and C-2F shared-path regressions. No full Maven suite, browser suite, live provider, PostgreSQL or port 8081 process was used.

## Reconciled remaining exposure

The reconstructed matrix identifies these remaining limitations:

- Some JSON-backed CRUD families (wiki, attachments, comments/defects and related legacy records) remain partially remediated or unverified.
- Durable execution recovery and distributed worker claims remain outside the current in-memory H-004 architecture.
- Logs/result metadata and some notification/audit-log boundaries do not have independent current H-005 verification.
- Remote runners, webhooks and provider-specific integrations were not found as implemented resources; they remain not applicable unless introduced.

These are not silently marked resolved, and no additional family was implemented in C-2G.

## Conclusion

The selected residual CI/CD cache boundary is fixed and independently verified. The fix preserves project authorization and previous C-001/C-002, H-001/H-002/H-003/H-004, C-2A, C-2C, C-2E and C-2F controls within the executed targeted tests.

**Batch C-2G: PASS.**  
**H-005: OPEN; another one-family audit/remediation batch is required before closure.**  
**Production readiness: NOT APPROVED.**

STOP CONDITION MET. No C-2H, C-3, Level 4.1.8D/E, PostgreSQL, remote-runner, distributed-queue or cloud work was started.
