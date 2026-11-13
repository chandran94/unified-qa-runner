# Level 4.1.8C-2A — Batch 1 Remediation Plan

## Selected resource families

1. `automationExecutions` persisted result records.
2. The H-004 background write path that creates those records.

These are one end-to-end boundary, not two independent storage migrations.

## Root cause

`AutomationExecutionManager.save` accepts an arbitrary map and persists it to shared `qa-runner.json` without requiring organization/project ownership. `RunnerServer.persistAutomationExecution` saves the result first and assigns `AUTOMATION_EXECUTION` ownership afterward. The GET route filters through the related `TEST_CASE`, so current route behavior is often fail-closed, but the record has no intrinsic authoritative owner and the write ordering permits an unowned persisted result.

## Proposed minimal change

- Add explicit `organizationId` and `projectId` metadata to worker-created execution records.
- Derive both only from the immutable H-004 `TenantJobContext` and authoritative project mapping.
- Reject save requests missing either field or with a project/organization mismatch.
- Assign `AUTOMATION_EXECUTION` ownership from the same context, never from a client field or filename.
- Filter `/api/automation-executions` by the execution ownership index before serialization; retain optional test-case/asset filters only as narrowing predicates.
- Treat legacy records without explicit ownership as inaccessible/quarantined unless the existing authoritative migration index proves the relationship; do not silently assign ownership.
- Preserve atomic compatibility-store writes and secret redaction.

## Expected behavior

| Case | Expected result |
|---|---|
| Authorized project member reads own result | Result returned |
| Same-org member without project membership | Denied/filtered |
| Different organization or project | Denied/filtered |
| Missing/tampered organization or project metadata | Save rejected; no result write |
| Revoked/suspended requester at worker boundary | Result write rejected |
| Unassigned/quarantined legacy result | Omitted from tenant list |
| Authorized result update/retry with same immutable owner | Idempotent update succeeds |
| Secret-bearing fields | Not added to record or response |

## Required tests

- manager rejects missing ownership and mismatched ownership;
- authorized same-project list returns only owned records;
- foreign and unassigned records are filtered;
- worker context fields are propagated into persisted metadata;
- tampered context cannot be converted into a valid owner;
- idempotent update preserves owner;
- existing C-001/C-002/H-001/H-002/H-003/H-004 suites remain green.

## Deferred families

Reports/artifacts, CI/CD and provider caches, database connections, test-case/story/mapping stores, shared paths/imports, runner registration, distributed claims, physical tenant namespaces, restart durability, and PostgreSQL remain deferred. A later batch is required for those boundaries.

## Regression risks

- Existing unit fixtures that save unowned execution maps must be updated to represent valid ownership or explicitly test rejection.
- Legacy records may disappear from tenant lists, which is intentional quarantine behavior.
- No browser or real plugin execution is required; the selected store boundary is tested with disposable maps and deterministic authorization fixtures.
