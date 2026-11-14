# Level 4.1.7D Focused Team Management Verification — Final Report

## Executive result

The five controls are **not fully runtime-verified**. Existing isolated Java tests passed, while the UI refresh control and several route-specific management behaviors remain source-reviewed or partially verified. No production defect was confirmed, but this phase does not support a blanket `VERIFIED` classification.

## Test evidence

| Command | Result |
|---|---|
| `mvn -Dtest=TenantContextResolverTest test` | PASS — 6/6, 0 failures/errors/skips |
| `mvn -Dtest=OrganizationApiIntegrationTest test` | PASS — 3/3, 0 failures/errors/skips |
| Combined unique executed tests | 9/9 PASS |
| Browser/UI tests | NOT RUN — no approved isolated browser harness |
| Full Maven suite | NOT RUN |

The tests used disposable temporary storage. The HTTP test constructed `RunnerServer` with an explicit temporary root and port `0`, which is OS-assigned. No test accessed the live application or port 8081.

## Control classifications

| Control | Final classification | Main gap |
|---|---|---|
| SUSPENDED membership persistence and active-member exclusion | PARTIALLY VERIFIED | No explicit HTTP/UI assertion covering the complete revoke-to-render path |
| Historical membership/audit preservation | SOURCE-REVIEWED / PARTIAL | No executed audit-history retention assertion |
| `sprints` / `sprint-board` authorization | PARTIALLY VERIFIED | Resolver evidence passed; route-specific Sprint Board HTTP tests were not run |
| Server-authoritative organization role | PARTIALLY VERIFIED | No explicit forged-role response test |
| Stale UI refresh ordering | SOURCE-REVIEWED | No browser/runtime test |

## Validation and scope

- Production source changed by this phase: none.
- Test source changed by this phase: none.
- `pom.xml` changed by this phase: none.
- Application configuration/data changed: none.
- External services accessed: none.
- Live server restarted/interrupted: no.
- Port 8081: untouched.
- Commit/push: none.
- H-005: CLOSED, unchanged.
- D1-F-001: OPEN, unchanged.
- D1-F-002: OPEN, unchanged.
- Production readiness: NOT APPROVED.

## Recommendation

Retain the existing finding statuses. Treat Level 4.1.7D as **PARTIALLY VERIFIED / SOURCE-REVIEWED**, not as complete runtime verification. If formal runtime closure of these controls is required, approve a separate isolated route/browser verification phase; do not use the live application.

