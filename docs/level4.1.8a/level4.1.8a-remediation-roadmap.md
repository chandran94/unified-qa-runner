# Level 4.1.8A Remediation Roadmap

## 4.1.8B — Core resource CRUD tenant isolation

**Scope:** `RunnerServer` route authorization, `TenantContextResolver`, `TenantAuthorizationPrimitives`, `OrganizationStore`, `ProjectOwnershipManager`, `TeamMemberManager`, `TeamRoleManager`, `UserStoryStore`, `TestCaseManager`, `DefectManager`, `WikiManager`, `AutomationMappingManager` and `DatabaseConnection*`.

**First items:** database connections (C-001), related links (H-002), bulk artifact guard (H-003), and every legacy `requireProjectAccess` route. Add organization/project ownership fields or authoritative relationship indexes. Quarantine unresolved records; never infer ownership.

**Acceptance criteria:** every list filters before serialization; every object/nested/bulk operation resolves the authoritative owner; project membership remains required; cross-org requests fail closed; revoked membership fails immediately; 401/403/404 contracts are documented and tested; restart preserves ownership.

**Tests:** two-organization CRUD/IDOR matrix for every resource, nested and bulk operations, revoked sessions, missing ownership, pagination/search, error disclosure, and persistence reload.

**Effort:** Large, approximately 2–4 engineering weeks for the current file-backed model. **Regression risk:** high, because legacy endpoints and UI assumptions currently depend on global stores.

## 4.1.8C — Execution, runners and CI/CD tenant isolation

**Scope:** `RunnerServer` run lifecycle, `ExecutionService`, `RunnerPlugin` implementations, `AutomationExecutionManager`, `CiCdConfig`, `PipelineStatusService`, `WorkflowGenerator`, and future runner-registration/webhook components.

**Acceptance criteria:** each job persists immutable organization/project/requester context; queue claim and cancellation are tenant-scoped; authorization is rechecked before execution and result access; retries preserve context; artifacts/logs/reports inherit ownership; CI/CD settings and secrets are per organization/project; token values never enter logs or responses; webhook signatures and tenant mappings are validated.

**Tests:** queue/retry/revocation race tests, two-tenant runner claim tests, run/report/log/artifact IDOR tests, CI/CD concurrent configuration tests, token-redaction tests, restart tests, and signed webhook tests when webhooks are implemented.

**Effort:** Large, approximately 3–5 engineering weeks. **Regression risk:** high for execution behavior, provider integration and local filesystem assumptions.

## 4.1.8D — Files, attachments, reports, exports and integrations

**Scope:** artifact directories, attachment upload/download, `ReportWriter`, report exports, shared paths/imports, database connection tests, external provider adapters and backup/restore.

**Acceptance criteria:** tenant/project namespace separation is explicit; canonical path plus symlink policy is enforced; temporary files are isolated; download/export/list/delete all authorize the same owner; retention and secure deletion are documented; secrets are redacted; provider configuration has owner, audit, rotation and failure isolation.

**Tests:** traversal/symlink tests, guessed IDs/paths, bulk operations, export authorization, content-type/size validation, tenant restart/restore, provider timeout and secret-redaction tests.

**Effort:** Medium-to-large, approximately 2–4 engineering weeks. **Regression risk:** medium/high due to existing shared `target` directories and compatibility data.

## 4.1.8E — Full multi-tenant security and browser E2E validation

**Scope:** one bounded browser instance, organization switching, stale request cancellation, revoked sessions, all resource families, API/IDOR matrix, execution and integration boundaries.

**Acceptance criteria:** no cross-org data in lists, nested responses, exports, files, reports, logs or errors; authorized project access works in multiple organizations; unauthorized project access remains denied; stale requests cannot overwrite tenant state; all critical/high tests pass; production readiness review remains separate.

**Effort:** Medium, approximately 1–2 engineering weeks after B–D. **Regression risk:** medium; browser timing and legacy UI assumptions are the main risks.

## Dependencies and sequencing

1. Approve 4.1.8B before changing authorization rules.
2. Resolve ownership schema and quarantine policy before moving data.
3. Complete B before C so execution receives an authoritative owner.
4. Complete C before D so artifact/report owners have a durable source.
5. Run E only after B–D implementation and focused regression suites.
6. PostgreSQL is intentionally out of scope for this level; its schema should be designed only after the ownership contract is approved.

## Do not do in the next phase

Do not grant access from organization membership alone where project membership is required. Do not auto-assign legacy records. Do not weaken platform-admin boundaries. Do not start PostgreSQL, 4.1.8C–E, or production approval without explicit authorization.

