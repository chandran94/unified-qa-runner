# H-005 Formal Change Inventory

## Review scope

Audit-only review of the current worktree and the latest H-005 storage/mapping remediation. No application, test, configuration, or Git state was changed by this review.

## Git state

- Branch: `master`
- HEAD: `d9c99e56e4289c31928dad5a071a552bd75555dc`
- Staged changes: none.
- Tracked modified files: 7, not the reported 8.
- Untracked files: 71, consisting of prior security reports, test fixtures/tests, and one report-security helper.
- Commits/pushes: none performed.

Tracked modified files:

1. `src/main/java/com/example/unifiedqarunner/AutomationMappingManager.java`
2. `src/main/java/com/example/unifiedqarunner/PipelineStatusService.java`
3. `src/main/java/com/example/unifiedqarunner/RemoteImportConfig.java`
4. `src/main/java/com/example/unifiedqarunner/RunnerServer.java`
5. `src/main/java/com/example/unifiedqarunner/TestCaseManager.java`
6. `src/main/resources/static/index.html`
7. `src/test/java/com/example/unifiedqarunner/TenantRouteIntegrationTest.java`

The reported “8 files changed, +59/-28” cannot be reconciled with the current tracked status. The eighth item may have been an untracked file or a prior snapshot; this review does not infer which. The untracked files were not staged, reverted, or deleted.

## Relevant change review

| File | Classification | Review result |
|---|---|---|
| `RunnerServer.java` | Production | Mapping GET authorization now uses persisted `testCaseId` ownership. This is the approved contract correction. Other changes in this file predate this review and require separate ownership review. |
| `TestCaseManager.java` | Production | Explicit `Path` constructor with default-preserving constructor; storage injection evidence reviewed. |
| `AutomationMappingManager.java` | Production | Equivalent explicit `Path` constructor; mappings without `executionId` remain representable. |
| `TenantRouteIntegrationTest.java` | Test | Existing tenant regression changes; not modified by this review. |
| `PipelineStatusService.java` | Production | Prior C-2G cache isolation change; not part of the mapping contract correction. |
| `RemoteImportConfig.java` | Production | Prior import/path-safety change; not part of the mapping contract correction. |
| `index.html` | Frontend | Prior security/remediation change; not part of the mapping contract correction. |

The current mapping change does not weaken authentication, RBAC, CSRF, same-origin checks, project ownership, or legacy fail-closed behavior. It removes an invalid dependency on optional `executionId` during mapping-list authorization.

