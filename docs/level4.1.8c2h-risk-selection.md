# Level 4.1.8C-2H — Residual Risk Selection

## Decision

**Audit-only; no application family selected for remediation.**

The original H-005 scope has been reconciled against current source and focused test evidence. C-2A through C-2G cover the confirmed reachable boundaries that were independently fixable in prior batches. The remaining items are either:

- partially remediated families requiring a broader CRUD or durable-storage audit;
- unverified families where the exact route/store contract is incomplete;
- not-applicable features with no implementation;
- architecture limitations requiring a separate approved design, such as durable execution recovery.

Selecting one of these without a complete ownership contract would risk a partial fix and would not be evidence-based.

## Ranked residual risks

| Rank | Family | Evidence classification | Risk | Why not selected in C-2H |
|---:|---|---|---|---|
| 1 | Logs, plugin results and durable execution history | Partially remediated | High | Coupled to execution lifecycle, retention, artifact paths and restart semantics; needs a broader C-3/D contract |
| 2 | Wiki and remaining JSON-backed CRUD/attachments | Unverified/partially remediated | High | Route-by-route ownership and legacy behavior are incomplete; no safe single endpoint/store slice established |
| 3 | Test-case/mapping/discovered-asset namespace | Partially remediated | Medium | Closely coupled to catalog, import and execution selection already covered by C-2E/C-2F |
| 4 | Durable external pipeline history | Not currently implemented | Medium | Current provider results are transient; no durable shared run store exists |
| 5 | Remote runner registration/webhooks | Not applicable | Low | No current routes, stores, tokens, claims or callback implementation |

## Reconciled prior high-risk boundary

C-2G fixed the last confirmed residual cache issue found during the preceding reconciliation: `PipelineStatusService` previously keyed status cache entries only by provider/repository URL. It now carries the authoritative project ID from `RunnerServer` through status and auto-sync calls and does not cache unscoped service calls. The focused cache-scope test and the prior targeted security set passed.

## Evidence needed before H-005 closure

- Complete route-level tests for every partially remediated JSON-backed resource.
- Explicit ownership/retention/redaction tests for logs, plugin outputs, exports and restart behavior.
- A separately approved durable execution/history design if crash recovery or distributed workers are required.
- Reassessment of integrations only when actual provider/callback stores are implemented.

## Scope boundary

No application code, data, credentials, migration, external provider, browser, PostgreSQL, cloud, runner or queue architecture was changed or started in C-2H.
