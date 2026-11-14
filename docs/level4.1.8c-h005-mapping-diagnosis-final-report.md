# H-005 Automation Mapping Contract Diagnosis

## Final classification

**F. PRODUCTION API CONTRACT DEFECT.** The request and fixture are valid. The mapping list handler requires `executionId` on each persisted mapping, while supported mapping creation does not write that field.

## Results

- Storage injection: 3/3 PASS.
- Test-case CRUD HTTP: 1/1 PASS.
- Mapping creation: HTTP 201 PASS.
- Authorized mapping list: FAIL, HTTP 400 `Enter a value for executionId`.
- Mapping update: NOT IMPLEMENTED.
- Targeted prior regressions: 23/23 PASS.
- Maven compile: PASS in the preceding approved phase.
- `git diff --check`: PASS in the preceding approved phase.
- Production changes in this diagnosis: NONE.
- Test-only changes in this diagnosis: NONE.
- Port 8081: untouched.

## Root cause

`RunnerServer.java:527` calls `stringField(item, "executionId")` while authorizing mappings as `AUTOMATION_EXECUTION` resources. `AutomationMappingManager.java:39-65` creates mappings without `executionId`. The generic error originates at `RunnerServer.java:3166`.

## Closure decision

H-005 remains **OPEN**. Recommendation: **NOT ELIGIBLE — REMEDIATION REQUIRED**, followed by a separately approved focused mapping authorization test batch. Production readiness remains **NOT APPROVED**. No other Level 4.1.8 phase was started.
