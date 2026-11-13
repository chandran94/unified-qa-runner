# Level 4.1.8B-2 High Findings Review

Date: 2026-11-14  
Scope: Batch A review and Batch B remediation planning only

## Evidence reviewed

- [Level 4.1.8A final report](./level4.1.8a-final-report.md)
- [API authorization matrix](./level4.1.8a-api-matrix.md)
- [Resource ownership matrix](./level4.1.8a-resource-ownership-matrix.md)
- [Execution security audit](./level4.1.8a-execution-security-audit.md)
- [Remediation roadmap](./level4.1.8a-remediation-roadmap.md)
- [Level 4.1.8B-1 final report](./level4.1.8b1-final-report.md)

All five High IDs in the audit are present in the latest source. No report contradiction or missing evidence was found.

## Findings inventory

### H-001 — Legacy project authorization is separate from tenant resolution

- **Severity:** HIGH.
- **Affected surface:** Legacy project-scoped routes across `RunnerServer`, including compatibility user-story, team, execution and related resource branches.
- **Source evidence:** [RunnerServer.java](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:2686) `tenantContextForProject` and [RunnerServer.java](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:2836) `projectAccessForDomain`, contrasted with legacy `requireProjectAccess` branches. `requireAdminForUnownedResource` remains the fail-closed path for unresolved data.
- **Root cause:** Two authorization contracts coexist: resolver-backed organization/project authorization and direct `TeamAuthorization`/`ProjectOwnershipManager` checks. Some legacy records have no authoritative organization assignment.
- **Exploit preconditions:** Authenticated user must reach a legacy route with a project/resource identifier whose ownership path is not resolver-backed; unresolved records are generally admin-only, reducing but not eliminating exposure.
- **Potential impact:** Cross-organization or cross-project access if a legacy route trusts a project identifier without validating the organization context; inconsistent denial/status behavior.
- **Existing behavior:** Many routes use `requireProjectAccess`; unresolved resources are denied to non-global-admin users through `requireAdminForUnownedResource` or ownership helpers. This is not a complete consolidated contract.
- **Required checks:** Resolve authoritative project-to-organization ownership; require active organization membership and explicit project membership; preserve domain permission, platform-admin separation, and quarantine for unresolved records.
- **Dependencies:** Broad route-by-route migration; `TenantContextResolver`, `OrganizationStore`, ownership indexes, frontend tenant query propagation, and legacy-data quarantine policy.
- **Existing tests:** Resolver, organization API/store, project ownership, team authorization and selected tenant routes.
- **Missing tests:** Complete legacy-route matrix, nested resources, revoked sessions, list filtering, status contract consistency and compatibility-route IDOR tests.
- **Estimated effort:** Large, approximately 2–4 engineering weeks.
- **Regression risk:** High because legacy UI and shared stores depend on the existing contract.

### H-002 — Related-link mutation lacks object ownership enforcement

- **Severity:** HIGH.
- **Affected endpoints:** `PUT /api/user-stories/related-links/{id}` and `DELETE /api/user-stories/related-links/{id}`.
- **Source evidence:** [RunnerServer.java](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:959) directly calls `updateRelatedLink` and [RunnerServer.java](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:974) calls `findRelatedLink`/`removeRelatedLink` without resolving the link’s story/project first. [UserStoryStore.java](../../src/main/java/com/example/unifiedqarunner/UserStoryStore.java:496) stores the link’s `storyId`.
- **Root cause:** The route validates same-origin/CSRF and link payload, but does not bind the link ID to the owning story and project before mutation.
- **Exploit preconditions:** Authenticated user with CSRF-valid mutation access and a guessed related-link ID.
- **Potential impact:** Modification or deletion of another project’s link, including cross-organization data if the ID is known.
- **Existing behavior:** CSRF and same-origin are enforced; no object-level ownership check is performed at these two branches.
- **Required checks:** Resolve link ID server-side to story ID; resolve story to authoritative project; require active tenant/project authorization with `USER_STORIES` write permission; deny missing/unassigned ownership and do not permit ownership reassignment through payload fields.
- **Dependencies:** A small `UserStoryStore` story/project lookup and existing project/tenant authorization helper; no schema migration required.
- **Existing tests:** General user-story and organization tests; no related-link IDOR matrix was found.
- **Missing tests:** Same-project owner/admin/member authorization, unauthorized project denial, cross-organization denial, unauthenticated/CSRF responses, invalid IDs and immutable ownership.
- **Estimated effort:** Small, approximately 0.5–1 engineering day including tests.
- **Regression risk:** Medium; legitimate link editing must continue to work and frontend requests may need tenant scope if resolver-backed enforcement is used.

### H-003 — Bulk artifact deletion is not ownership-scoped

- **Severity:** HIGH.
- **Affected endpoints:** `DELETE /api/screenshots` and `DELETE /api/videos`.
- **Source evidence:** [RunnerServer.java](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:678) and [RunnerServer.java](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:693) dispatch collection deletion; [RunnerServer.java](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:3186) and [RunnerServer.java](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:3224) delete every regular file in the shared directory without `artifactVisible` or `requireArtifactAccess`.
- **Root cause:** Individual artifact operations use `ProjectOwnershipManager`, but bulk deletion bypasses that object-level filter and treats the shared directory as one tenant.
- **Exploit preconditions:** Authenticated user who passes the route’s current CSRF/same-origin gate and can invoke a collection delete.
- **Potential impact:** Destruction of screenshots/videos belonging to other projects or organizations; unresolved legacy files can also be deleted.
- **Existing behavior:** Individual list/read/delete paths filter or require artifact ownership. Bulk delete does not. Global admin behavior is intentionally broader and must remain separate.
- **Required checks:** For each file, require resolved ownership and project write permission before deletion; preserve unresolved/quarantined files; return counts for only authorized deletions; deny ordinary users when no authorized artifacts are present if that is the established contract.
- **Dependencies:** Existing `artifactVisible`/`requireArtifactAccess`, ownership index, and artifact filename validation. No database migration required.
- **Existing tests:** Individual artifact ownership is covered only partially by source and existing tests.
- **Missing tests:** Two-project/two-organization bulk delete, unauthorized files preserved, unassigned files preserved, admin behavior, invalid paths, CSRF and unauthenticated requests.
- **Estimated effort:** Small, approximately 0.5–1 engineering day including tests.
- **Regression risk:** Medium; existing “delete all” UI semantics may change from global cleanup to authorized-scope cleanup.

### H-004 — Asynchronous run context is not an explicit durable tenant context

- **Severity:** HIGH.
- **Affected surface:** Run queue, executor, cancellation, retries, result/artifact persistence and report retrieval.
- **Source evidence:** [RunnerServer.java](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:1950) queues work through one executor; the run entry carries references but no immutable organization/requester authorization snapshot, and ownership is derived later around [RunnerServer.java](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:1954).
- **Root cause:** Tenant/project context is inferred from related records after queueing rather than persisted and revalidated at execution boundaries.
- **Exploit preconditions:** Queue/revocation/retry/restart race or future worker boundary where related ownership changes between request and execution.
- **Potential impact:** Execution, cancellation, logs, artifacts or reports could be attributed to the wrong tenant if context is lost.
- **Existing behavior:** Initial run creation has ownership checks and report retrieval has ownership checks; durable async propagation and revocation-at-execution are not established.
- **Required checks:** Persist immutable organization/project/requester context, revalidate active membership and permissions before execution/cancellation/result access, and retain context across retry/restart.
- **Dependencies:** Execution model, `ExecutionService`, runner/plugin boundaries, artifact/report ownership and likely future persistence schema.
- **Existing tests:** Run creation and selected report ownership behavior.
- **Missing tests:** Revocation races, retries, restart, cancellation IDOR, worker claim isolation and artifact/report inheritance.
- **Estimated effort:** Large, approximately 3–5 engineering weeks.
- **Regression risk:** High due to execution timing and shared filesystem assumptions.

### H-005 — Integration and execution stores are process/project-root scoped

- **Severity:** HIGH.
- **Affected resources:** `UserStoryStore`, `TestCaseManager`, `AutomationMappingManager`, `AutomationExecutionManager`, `SharedPathConfig`, `RemoteImportConfig`, `WikiManager`, and shared artifact directories.
- **Source evidence:** [ProjectOwnershipManager.java](../../src/main/java/com/example/unifiedqarunner/ProjectOwnershipManager.java:15) documents additive ownership indexing; its migration logic quarantines several legacy families, while the stores remain in `qa-runner.json` or shared project-root paths.
- **Root cause:** Ownership is not intrinsic to many records and is derived only where a trustworthy relationship exists. Shared storage has no physical tenant namespace.
- **Exploit preconditions:** Authenticated access to a route that does not consult the ownership index, legacy records with ambiguous relationships, or future integration/background code that reads shared stores directly.
- **Potential impact:** Cross-project or cross-organization reads, writes, exports, execution inputs or artifact disclosure.
- **Existing behavior:** Some routes filter through `ProjectOwnershipManager`; unresolved records are denied or admin-only. Coverage is incomplete and not equivalent to a full tenant-aware persistence model.
- **Required checks:** Add authoritative ownership to each resource family or a complete relationship index; quarantine ambiguous records; apply ownership before list serialization and background processing; isolate file namespaces and secrets.
- **Dependencies:** Core CRUD isolation, execution context, files/reports/integrations, and eventual database schema design.
- **Existing tests:** Selected store, ownership and tenant-route tests.
- **Missing tests:** Full resource-family IDOR, exports, background jobs, provider configuration, restart/restore and race coverage.
- **Estimated effort:** Large, approximately 3–6 engineering weeks across subsequent batches.
- **Regression risk:** High because current compatibility data is shared and legacy.

## Batch B selection and remediation plan

Selected findings: **H-002 and H-003**.

Selection rationale: both have clear existing authoritative relationships, are independent of the durable execution architecture, and require small route/helper changes. They represent direct destructive or mutating cross-tenant exposure.

Plan:

1. Add a server-side related-link-to-story/project lookup and enforce existing project authorization before update/delete.
2. Change screenshot/video bulk deletion to delete only artifacts with resolved, authorized ownership; preserve unresolved/quarantined files.
3. Add isolated disposable-storage integration tests covering authorization, cross-tenant denial, CSRF/authentication, invalid IDs, filtering and immutable ownership.
4. Run only compile, focused tests, relevant tenant/organization/Level 3 regressions, and `git diff --check`.

Deferred to future approval: H-001, H-004 and H-005. Batch C and later levels are not started by this review.
