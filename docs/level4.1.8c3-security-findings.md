# Level 4.1.8C-3 — Security Findings

## Confirmed vulnerabilities

**None confirmed by the executed HTTP verification.** Organization A did not successfully read, mutate or delete the tested Organization B resources. No production authorization code was changed.

## Coverage limitations (not findings)

### C3-GAP-001 — Incomplete report object HTTP matrix

- Severity: HIGH verification gap, not a confirmed vulnerability.
- Evidence: `ReportStorageSecurityTest` and `RunnerReportTest` exercise path and report behavior, but no two-organization HTTP fixture guessed a foreign run report ID.
- Impact if unverified: report IDOR or path/ownership regressions could remain undetected.
- Recommendation: add a disposable two-tenant run/report fixture and exercise authorized, foreign, traversal, symlink and non-regular-file cases before closure.

### C3-GAP-002 — Incomplete attachment and plugin-output HTTP matrix

- Severity: MEDIUM/HIGH verification gap, not a confirmed vulnerability.
- Evidence: source and prior route tests show parent authorization and path handling, but the required two-organization upload/download/delete and plugin-log matrix was not executed.
- Recommendation: test each parent-child route with disposable files and verify no absolute path, secret or foreign metadata disclosure.

### C3-GAP-003 — Durable/restart execution boundaries not tested

- Severity: HIGH architecture/verification gap, not a confirmed vulnerability.
- Evidence: H-004 remains an in-memory queue; `TenantJobContext` tests cover propagation/revalidation, but crash recovery and durable worker claims do not exist.
- Recommendation: separately approve a durable execution design and test it; do not infer distributed-worker security from the current local executor.

## Test development note

The first run of the new test class exposed invalid test assumptions: the fixture used the application’s platform-level `Admin` role, and an assertion treated the generic word “token” as secret disclosure. The fixture was changed to `Project Manager`, and the assertion was narrowed to fixture secrets. The final run passed without application changes. These were test defects, not application security findings.
