# Level 4.1.8C-2B — H-005 Risk Comparison

Status: completed against current source and prior audit reports. This comparison selects exactly one resource family.

## Evidence reviewed

- [Level 4.1.8A execution audit](./level4.1.8a/level4.1.8a-execution-security-audit.md)
- [Level 4.1.8C-1 report](./level4.1.8c1-final-report.md)
- [C-2A ownership matrix](./level4.1.8c2a-h005-store-ownership-matrix.md)
- [C-2A remediation plan](./level4.1.8c2a-remediation-plan.md)
- [C-2A final report](./level4.1.8c2a-final-report.md)

## Candidate comparison

| Candidate | Current evidence | Highest-risk concern | Independent fixability | Decision |
|---|---|---|---|---|
| A. Shared execution artifacts, metadata, downloads and reports | Individual artifact routes use `ProjectOwnershipManager`; H-003 bulk deletion filters assigned artifacts. Physical report/screenshot/video namespaces remain shared, report writers are plugin-driven, and symlink/retention behavior is not fully verified. | Cross-tenant artifact exposure or deletion through shared paths; safe namespace migration requires coordinated execution, artifact attribution, and storage changes. | Limited without changing `ExecutionService` output paths and all plugin writers. | Deferred |
| B. CI/CD execution records, result imports and associated logs | Configuration read/write/status routes are project-scoped, but `/api/testcases/sync` was classified as unowned/Admin-only and loaded the legacy global `ciCd` configuration. It could affect test-case state without a project-bound import authorization path. | Credential-backed external reads plus tenant-affecting result imports and shared legacy configuration. | Small route/config boundary with existing `TenantContextResolver`, CI/CD permission and CSRF helpers. | **Selected** |

## Selection rationale

Candidate B has the higher immediately exploitable boundary: it combines a tenant-mutating result import with a credential-bearing legacy configuration fallback. Candidate A has important remaining risk, but a safe complete fix requires a broader physical namespace and writer migration. Implementing only a path check would not establish end-to-end tenant ownership for plugin-created reports and artifacts.

## Scope boundary

Selected: CI/CD result import authorization and project-scoped configuration loading for `/api/testcases/sync`.  
Deferred: physical artifact namespaces, report/download storage migration, screenshots/videos beyond existing H-003 controls, remaining CI/CD status/cache architecture, PostgreSQL, and external callback/webhook work.
