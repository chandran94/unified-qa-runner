# H-005 Blocker Resolution Matrix

| Earlier blocker | Earlier evidence | Subsequent evidence | Current status | Remaining uncertainty |
|---|---|---|---|---|
| Test/mapping CRUD isolation | `closure-evidence-final-report.md`: blocked because managers used working-directory storage | `storage-injection-design.md`, `storage-injection-implementation.md`, `mapping-contract-tests.md`; injected paths and disposable HTTP root | RESOLVED within tested scope | Other unrelated stores remain outside this mapping slice. |
| Mapping listing HTTP 400 | `final-gate-report.md` / mapping diagnosis: authorized list failed | `mapping-contract-implementation.md`: list authorizes through `testCaseId`; focused HTTP test passed | RESOLVED | No in-place mapping update API exists. |
| `executionId` contract mismatch | Mapping creation omitted `executionId`; list path required it | `executionid-root-cause.md` and contract implementation: optional association accepted; no fabricated ID | RESOLVED | Existing legacy data with contradictory ownership still fails closed. |
| Authoritative `testCaseId` ownership | Earlier list path used `executionId` | Current `RunnerServer` mapping GET uses `TEST_CASE` plus persisted `testCaseId` | VERIFIED | Evidence is focused on implemented mapping operations. |
| Cross-tenant mapping authorization | Earlier mapping family partial/blocked | `FinalH005CrudHttpVerificationTest`: OrgB cannot list/create/delete OrgA mapping; forged project relationship denied | VERIFIED | No full production deployment test was performed. |
| Legacy/unassigned mappings | Original audit required quarantine/fail-closed behavior | Current ownership helper denies unresolved ownership; reports classify unassigned resources fail closed | VERIFIED within tested scope | Broader legacy populations were not migrated and remain unavailable to ordinary users. |
| Mapping update | Requested as a possible operation | Source review found no mapping PUT/PATCH route or manager update method | NOT IMPLEMENTED | Future implementation would require a new security review. |
| Original 14-family verification | Historical coverage 11/14 then 13/14 | Latest matrix reconciles the final implemented family | RESOLVED within implemented scope | Some families contain unimplemented or partially evidenced sub-capabilities. |
| Focused tests 5/5 | Not available at earlier gate | StorageRootInjectionTest 3/3 plus FinalH005CrudHttpVerificationTest 2/2 | VERIFIED | Test result is current focused evidence, not a production approval. |
| Targeted regressions 23/23 | Earlier gate had different 46/46 set | Latest targeted command reports 23/23 PASS | VERIFIED | Counts are distinct from earlier suites. |
| Unique tests 28/28 | Not available at earlier gate | 5 focused + 23 targeted, no class overlap | VERIFIED | No full Maven suite was run. |

## Conclusion

Every blocker that prevented the final implemented mapping family from being verified has subsequent source/test evidence. The remaining limitations are explicit scope boundaries, not unresolved contradictions in the historical decisions.

