# Level 4.1.8A — Tenant Isolation & Security Architecture Audit

## Scope and method

This is a read-only source and existing-test audit of `unified-qa-runner`. No application source, configuration, data, server process, browser, PostgreSQL instance, or external provider was changed or started. Existing worktree changes were preserved.

The route inventory covers 140 explicit route-condition branches in `RunnerServer.java`, plus the root/static pages, `/screenshots/*`, and `/videos/*` handlers. The resource review covers the 38 resource families requested in the level specification. Findings are based on source evidence and test-file inspection; source inspection alone is not treated as a security pass.

## Executive result

**Level 4.1.8A: BLOCKED.** The audit and documentation are complete, but the application is not ready for 4.1.8B approval because confirmed tenant-sensitive gaps remain. **Production readiness: NOT APPROVED.**

| Severity | Confirmed findings |
|---|---:|
| CRITICAL | 2 |
| HIGH | 5 |
| MEDIUM | 4 |
| LOW | 2 |

## Critical findings

### C-001 — Database connections are shared-store, membership-only resources

- **Evidence:** [`RunnerServer.java:2303`](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:2303)–[`RunnerServer.java:2319`](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:2319) authorizes database access from any active team member and role, without organization, project, or connection ownership. [`RunnerServer.java:389`](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:389)–[`RunnerServer.java:409`](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:409) exposes list/get/update/delete/test/cancel operations. [`DatabaseConnectionRepository.java:17`](../../src/main/java/com/example/unifiedqarunner/DatabaseConnectionRepository.java:17) stores all connections under one `databaseConnections` key in `qa-runner.json`.
- **Affected tenant/resource:** Database connection definitions, credentials metadata, connection tests, and live-connect operations.
- **Preconditions/impact:** An authenticated active team member in any organization can reach the shared connection set. This can disclose or alter another tenant’s connection configuration and can initiate a test against another tenant’s target.
- **Remediation:** Make organization and project ownership mandatory for every connection and test job; resolve ownership server-side before list serialization and every object operation; scope decrypted credentials and connection-test state to the same owner.
- **Verification:** Two organizations with separate connections; list/get/update/delete/test/connection-test IDOR tests; revoked-member tests; no credential fields in responses; restart persistence test.

### C-002 — CI/CD configuration is a single shared slot with tenant-sensitive token use

- **Evidence:** [`CiCdConfig.java:69`](../../src/main/java/com/example/unifiedqarunner/CiCdConfig.java:69) loads one `ciCd` object, while [`CiCdConfig.java:128`](../../src/main/java/com/example/unifiedqarunner/CiCdConfig.java:128)–[`CiCdConfig.java:155`](../../src/main/java/com/example/unifiedqarunner/CiCdConfig.java:155) stores only one `projectId`. [`RunnerServer.java:2890`](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:2890) checks that one current project, but [`RunnerServer.java:3849`](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:3849)–[`RunnerServer.java:3863`](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:3863) reads the shared configuration. Commit execution logs token-derived data at [`RunnerServer.java:3994`](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:3994)–[`RunnerServer.java:4002`](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:4002). The encryption key is a source constant at [`CiCdConfig.java:25`](../../src/main/java/com/example/unifiedqarunner/CiCdConfig.java:25)–[`CiCdConfig.java:26`](../../src/main/java/com/example/unifiedqarunner/CiCdConfig.java:26).
- **Affected tenant/resource:** Repository tokens, CI/CD settings, workflow generation/commit, pipeline status and sync.
- **Preconditions/impact:** Multiple tenants/projects use the same runner data file. Configuration replacement and token use are not represented as tenant-scoped records; logs can expose token prefixes. The static key does not provide tenant or deployment isolation.
- **Remediation:** Store one credential reference per organization/project in a secret manager, remove token logging, bind every read/write/test/generate/commit/status/sync operation to an authoritative project organization, and reject legacy singleton data until explicitly quarantined/migrated.
- **Verification:** Concurrent two-tenant config tests, token non-disclosure assertions, commit/status/sync IDOR tests, restart tests, and log-redaction checks.

## High findings

### H-001 — Legacy project authorization is separate from tenant resolution

[`RunnerServer.java:2686`](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:2686)–[`RunnerServer.java:2717`](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:2717) provides authoritative organization checks, but [`RunnerServer.java:2793`](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:2793)–[`RunnerServer.java:2803`](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:2803) uses `TeamAuthorization` directly. This creates two authorization contracts. Some legacy data is deliberately admin-only through [`RunnerServer.java:2633`](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:2633)–[`RunnerServer.java:2642`](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:2642), which is fail-closed but not a complete tenant model. 4.1.8B must consolidate all project-scoped routes on one resolver.

### H-002 — Related-link mutation lacks object ownership enforcement

[`RunnerServer.java:959`](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:959)–[`RunnerServer.java:975`](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:975) updates or deletes a related link after CSRF validation but does not resolve the link to its story/project and call project ownership authorization. A guessed link ID can therefore reach a mutation path without the same object-level check used by attachments and defects.

### H-003 — Bulk artifact deletion is not ownership-scoped

The collection delete routes at [`RunnerServer.java:678`](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:678)–[`RunnerServer.java:681`](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:681) and [`RunnerServer.java:693`](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:696) require same-origin/CSRF at the route, but [`RunnerServer.java:3132`](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:3150) and [`RunnerServer.java:3170`](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:3187) delete every file in the shared directory without filtering through `artifactVisible` or `requireArtifactAccess`.

### H-004 — Asynchronous run context is not an explicit durable tenant context

Runs execute through a single executor at [`RunnerServer.java:1950`](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:1950)–[`RunnerServer.java:1959`](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:1959). `RunEntry` carries test/asset references, but no organization ID or authenticated principal is persisted. Ownership is derived later from `TEST_RUN` and test-case relationships at [`RunnerServer.java:1954`](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:1954)–[`RunnerServer.java:1956`](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:1956). This is fail-closed for unowned runs, but it does not prove context propagation, revocation-at-execution, retry isolation, or runner/worker scoping.

### H-005 — Several integration and execution stores are process/project-root scoped rather than tenant-scoped

`UserStoryStore`, `TestCaseManager`, `AutomationMappingManager`, `AutomationExecutionManager`, `SharedPathConfig`, `RemoteImportConfig`, `WikiManager`, and artifact directories use `qa-runner.json` or shared project-root directories. Ownership is additive and unresolved records are quarantined by [`ProjectOwnershipManager.java:15`](../../src/main/java/com/example/unifiedqarunner/ProjectOwnershipManager.java:15)–[`ProjectOwnershipManager.java:19`](../../src/main/java/com/example/unifiedqarunner/ProjectOwnershipManager.java:19), but many records have no organization field. This is a material readiness gap even where current routes deny unresolved data.

## Medium and low findings

- **M-001:** AI assistant is authenticated but stateless and not tenant/project scoped at [`RunnerServer.java:1369`](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:1369); no conversation ownership, retention, or prompt-data boundary is present.
- **M-002:** Static authenticated pages are protected by session presence at [`RunnerServer.java:179`](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:179)–[`RunnerServer.java:184`](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:184), but page access itself does not resolve an organization; tenant enforcement begins at API calls.
- **M-003:** No WebSocket/SSE endpoint or webhook receiver was found; polling is client/API based. Those boundaries are not implemented, not security-verified.
- **M-004:** External provider integrations use application-level config and outbound calls; per-tenant network/credential policy, audit, retention and failure isolation are not established.
- **L-001:** Error text and operational logging include resource identifiers and integration details; review/redaction is incomplete for worldwide SaaS operation.
- **L-002:** Legacy unassigned resources are intentionally admin-only or quarantined, but there is no user-facing quarantine workflow or retention policy evidence.

## Coverage and test evidence

Existing tests cover authentication foundation, organization store/API, resolver, selected tenant routes, project ownership, team authorization, user-story persistence, and browser organization flows. The inspected test tree contains no complete matrix for database IDOR, CI/CD token/config isolation, bulk artifact deletion, related-link IDOR, runner registration, webhook authenticity, worker claims, report/export authorization, or race/retry tenant propagation. These are **NOT VERIFIED**, not passes.

## Required next phase

The highest-priority implementation scope is **4.1.8B: authoritative organization/project ownership for every CRUD-capable resource**, starting with database connections, CI/CD configuration, related links, bulk artifacts, and all legacy `requireProjectAccess` routes. Do not start 4.1.8C–E or PostgreSQL until that scope is approved.

## Audit deliverables

- [API matrix](./level4.1.8a-api-matrix.md)
- [Resource ownership matrix](./level4.1.8a-resource-ownership-matrix.md)
- [Execution security audit](./level4.1.8a-execution-security-audit.md)
- [Remediation roadmap](./level4.1.8a-remediation-roadmap.md)

