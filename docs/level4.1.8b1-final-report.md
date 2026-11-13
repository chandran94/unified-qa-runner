# Level 4.1.8B-1 Final Report — Critical Tenant Isolation Remediation

Date: 2026-11-14  
Project: Unified QA Runner

## Result

The two Critical findings from the Level 4.1.8A evidence were remediated and independently verified with isolated temporary storage. Level 4.1.8B-1 is **PASS within this phase**. Production readiness remains **NOT APPROVED**.

No browser, external provider, PostgreSQL, cloud deployment, or port 8081 activity was used.

## Critical findings and root causes

### C-001 — Database connections were not tenant-owned

Evidence: [Level 4.1.8A final report](./level4.1.8a-final-report.md), C-001; [RunnerServer.java](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:389); [DatabaseConnectionRepository.java](../../src/main/java/com/example/unifiedqarunner/DatabaseConnectionRepository.java:20).

Before the fix, database connections were persisted in one shared `databaseConnections` collection without an authoritative organization owner. Route authorization checked broad database capability/membership but did not bind the object, list, test state, connect, disconnect, update, or delete operation to the owning organization. Legacy records without ownership remain unassigned and are not returned by the new tenant-scoped routes.

### C-002 — CI/CD configuration and token state used a shared singleton

Evidence: [Level 4.1.8A final report](./level4.1.8a-final-report.md), C-002; [CiCdConfig.java](../../src/main/java/com/example/unifiedqarunner/CiCdConfig.java:96); [RunnerServer.java](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:2941).

Before the fix, CI/CD configuration used the shared `ciCd` store and a static fallback encryption key. Tenant-facing configuration, generation, status, commit, and test-connection routes did not consistently require an authoritative organization/project scope. The commit path also logged a token prefix. The legacy singleton remains available only to the existing platform-Admin-only test-case sync compatibility route; ordinary tenant CI/CD routes cannot use it.

## Modified files

- [RunnerServer.java](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java) — authoritative organization checks for database routes; organization/project checks for CI/CD routes; query allowlist; removed token-prefix logging.
- [DatabaseConnectionRepository.java](../../src/main/java/com/example/unifiedqarunner/DatabaseConnectionRepository.java) — persisted organization owner, scoped list/create/update operations, legacy unassigned records retained but excluded.
- [DatabaseConnectionService.java](../../src/main/java/com/example/unifiedqarunner/DatabaseConnectionService.java) — scoped database operations and test-state ownership resolution.
- [ConnectionTestService.java](../../src/main/java/com/example/unifiedqarunner/ConnectionTestService.java) — test-state to connection ownership lookup.
- [CiCdConfig.java](../../src/main/java/com/example/unifiedqarunner/CiCdConfig.java) — per-project configuration storage, configured encryption key, scoped load/save.
- [index.html](../../src/main/resources/static/index.html) — sends organization/project scope for database and CI/CD requests.
- [TenantRouteIntegrationTest.java](../../src/test/java/com/example/unifiedqarunner/TenantRouteIntegrationTest.java) — isolated two-organization regression coverage for both Critical resources.

## Authorization changes and behavior

| Case | Before | After | Evidence |
|---|---|---|---|
| Database list | Shared collection could be exposed after broad capability check | Requires active organization membership and filters before serialization | `GET /api/database/connections?organizationId=...` returns only that organization’s records |
| Database object guessed across organizations | No authoritative owner check | Returns 404 after owner mismatch | Cross-organization ID probe in `TenantRouteIntegrationTest` |
| Database test/connect/disconnect/update/delete | No object-to-organization binding | Organization is resolved first and object owner is checked | Scoped route branches in `RunnerServer.java` |
| CI/CD read/status/generate | Shared configuration slot | Requires active organization membership, explicit project membership, project ownership mapping, and read permission | `requireCiCdProjectAccess` |
| CI/CD save/commit/test | Shared configuration/token path | Requires write/manage permission and matching body/query organization/project IDs | Scoped CI/CD route handlers |
| Legacy unassigned database records | Could be treated as shared | Organization is blank and the secure routes deny/omit it | `fromMap` plus scoped repository filtering |
| CI/CD token response/logging | Token-bearing shared state and token-prefix log | Token remains redacted; commit log contains no token material | `CiCdConfig` safe response and `RunnerServer` commit logging |

The existing CSRF, session validation, role boundaries, `TenantContextResolver`, `TeamAuthorization`, and `ProjectOwnershipManager` contracts were retained. Client-supplied IDs are inputs only; authorization resolves and validates ownership server-side.

## Test and validation results

Focused Critical-resource test:

- `TenantRouteIntegrationTest`, `DatabaseConnectionManagerTest`, `TenantContextResolverTest`, `ProjectOwnershipManagerTest`: **13/13 passed**.
- Coverage includes authorized same-organization access, organization filtering, missing scope denial, cross-organization database ID guessing, cross-project CI/CD denial, project-scoped persistence, and token redaction.

Relevant tenant and Level 3 regression set:

- `TenantRouteIntegrationTest`
- `TenantContextResolverTest`
- `OrganizationApiIntegrationTest`
- `OrganizationStoreTest`
- `ProjectOwnershipManagerTest`
- `TeamAuthorizationTest`
- `DatabaseConnectionManagerTest`
- `RunnerServerTest`
- `AuthenticationFoundationTest`
- `LocalTwoAccountSecurityHarnessTest`

Result: **40/40 tests passed; 0 failures; 0 errors**.

Additional validation:

- Maven compile: **PASS** (`mvn -q -DskipTests compile`).
- `git diff --check`: **PASS**; no whitespace errors reported.
- Isolated temporary storage and dynamic test ports were used by the integration test.
- No real customer credentials or external systems were accessed.

Authorization HTTP evidence from the focused test:

- Unauthenticated protected organization request: **401**.
- Missing database organization scope: **403**.
- Cross-organization database object ID guess: **404**.
- Cross-project CI/CD request: **403**.
- Authorized database and CI/CD requests: **200/201** as applicable.
- CI/CD response did not contain the configured token.

## Remaining findings and gaps

Remaining Critical findings: **0 identified from the Level 4.1.8A Critical set**.

Remaining High findings from the audit remain out of scope for 4.1.8B-1:

- H-001: legacy project authorization paths separate from tenant resolution.
- H-002: related-link mutation ownership enforcement.
- H-003: bulk artifact deletion ownership enforcement.
- H-004: explicit durable tenant context for asynchronous runs.
- H-005: process/project-root-scoped integration and execution stores.

The platform-Admin-only `/api/testcases/sync` compatibility route still uses the legacy CI/CD singleton for its existing unowned-data behavior. It is not an ordinary tenant endpoint and remains a follow-up item for the execution/integration isolation phase. PostgreSQL migration, full browser revalidation, runner isolation, background-job isolation, files/artifacts, reports, exports, webhooks, and external integrations were not implemented or newly verified here.

## Regression failures

None in the targeted validation set. The initial focused run exposed only a missing query-parameter allowlist entry for database object routes; that narrow routing defect was corrected, and the rerun passed.

## Gate decision

**Level 4.1.8B-1: PASS within this phase.**

This does not approve production readiness. Do not begin Level 4.1.8B-2, 4.1.8C, 4.1.8D, 4.1.8E, or PostgreSQL migration without explicit approval.

Production readiness: **NOT APPROVED**.
