# Level 4.1.8C-3 — Original H-005 HTTP Coverage Matrix

The recovered original finding is H-005: process/project-root scoped integration and execution stores. Status below distinguishes executed HTTP evidence from unit/source-only evidence.

| Original family/reference | Current endpoint(s) | HTTP test/evidence | Outcome and coverage |
|---|---|---|---|
| Database connections, original C-001 overlap | `/api/database/connections`, `/api/database/connections/{id}` | `TenantRouteIntegrationTest.criticalResourcesAreScopedByAuthoritativeOrganizationAndProject` | PASS for list, cross-tenant ID read, missing scope and secret redaction; update/delete/connection-test branches NOT RUN |
| CI/CD configuration, original C-002 overlap | `/api/cicd/config`, `/api/cicd/test-connection` | `TenantRouteIntegrationTest.criticalResourcesAreScopedByAuthoritativeOrganizationAndProject` | PASS for scoped save/read, cross-tenant denial and token redaction; live test-connection NOT RUN |
| Test-case/pipeline result import | `/api/testcases/sync` | `TenantRouteIntegrationTest.pipelineResultImportRequiresScopedCiCdOwnershipAndIgnoresLegacyConfig` | PASS for forged project, missing scope, missing CSRF, unauthenticated and legacy-global denial |
| Automation executions, C-2A | `/api/automation-executions` | `TenantRouteIntegrationTest.automationExecutionResultsCarryExplicitOwnershipAndFilterBeforeSerialization`; new C-3 HTTP class | PASS for two-tenant list filtering and cross-tenant query exclusion |
| Local runs and execution result metadata, H-004 | `/api/runs`, `/api/runs/{id}` | `TenantRouteIntegrationTest` list coverage; `TenantJobContextTest`, `RunnerServerTest` | PARTIAL: list/context behavior tested; full cross-tenant object, revocation-at-execution and result persistence HTTP matrix NOT RUN |
| Report files, C-2C | `/api/runs/{id}/report` | `RunnerReportTest`, `ReportStorageSecurityTest` | PARTIAL: storage/path unit evidence PASS; two-organization HTTP report-ID test NOT RUN |
| Screenshots/videos, H-003 | `/api/screenshots`, `/api/videos`, `/screenshots/{file}`, `/videos/{file}` | `TenantRouteIntegrationTest.bulkArtifactDeletionFiltersByResolvedTenantOwnership` and browser-independent route assertions | PASS for screenshot list/bulk delete, CSRF, unauthenticated and ownership filtering; video-specific HTTP cases NOT RUN |
| Shared paths/catalog, C-2F | `/api/catalog`, `/api/shared-paths` | `SharedPathTenantIsolationTest`; new C-3 HTTP class | PASS for two-tenant filtering, absolute-path non-disclosure and project ownership |
| GitHub/ZIP imports, C-2E | `/api/imports`, `/api/imports/{id}/scan`, `/api/imports/{id}` | `RemoteImportTenantIsolationTest` | PASS for two-tenant catalog/import filtering and path redaction; live GitHub/ZIP upload is NOT RUN |
| Test cases, mappings and discovered assets | `/api/testcases`, `/api/testcase-automation-mappings`, catalog | `TenantRouteIntegrationTest` test-case create/read/update/delete and catalog-related assertions | PARTIAL: representative project ownership and IDOR cases PASS; complete route/mapping/search/bulk matrix NOT RUN |
| User stories/epics/sprints/boards | `/api/user-stories/*` | `TenantRouteIntegrationTest.legacyProjectRoutesUseAuthoritativeTenantResolution` | PASS for project read/create, wrong organization, cross-project, missing project, unauthenticated and CSRF cases; all CRUD branches NOT RUN |
| Wiki spaces/pages | `/api/wiki/*` | `TenantRouteIntegrationTest.tenantEnabledListsRequireMembershipAndFilterServerSide` | PARTIAL: organization-scoped list evidence; page object/mutation matrix NOT RUN in this HTTP phase |
| Attachments | `/api/user-stories/attachments/*`, story attachment routes | Source inspection and prior targeted tests only | BLOCKED/NOT RUN: no dedicated two-tenant attachment HTTP fixture in this phase |
| Logs/plugin results/history | Run response and plugin report directories | Source inspection, `RunnerReportTest` | BLOCKED/NOT RUN: no complete two-tenant log/result HTTP matrix |
| Pipeline status/cache, C-2G | `/api/cicd/status`; auto-sync branch of `/api/testcases/sync` | New C-3 HTTP class plus `PipelineStatusServiceTest` | PASS for authorized project status, forged cross-tenant scope denial and project cache-key separation; no live provider calls |
| Integration stores/webhooks | No confirmed callback/provider store route | Source route inventory | NOT APPLICABLE for absent webhook/remote-runner resources; future implementations require new tests |

## Coverage calculation

There are 14 implemented H-005-related families in this matrix, excluding not-implemented integration/webhook resources. Ten had at least one applicable two-organization HTTP verification in C-3: **10/14 = 71% endpoint-family coverage**. The remaining four families have partial or blocked coverage (full report object serving, attachments, logs/plugin results, and complete test/mapping CRUD). This is not complete original-audit coverage.

## Expected denial contract

Observed secure denials included 401 for unauthenticated access, 403 for cross-tenant/missing-CSRF/project authorization, and 404 for hidden direct objects where the existing route contract uses non-disclosure. No denial response in the executed tests contained fixture passwords, CI tokens or absolute paths from the foreign tenant.
