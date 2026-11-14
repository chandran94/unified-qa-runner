# H-005 `executionId` Root Cause

## Classification

**F. PRODUCTION API CONTRACT DEFECT.**

## Evidence chain

1. The test sends `GET /api/testcase-automation-mappings?testCaseId=<idA>` with an authenticated OrgA session and no request body.
2. `RunnerServer.java:523-533` accepts that route and defines only `testCaseId` and `assetId` as query filters.
3. At `RunnerServer.java:527`, the handler calls `stringField(item, "executionId")` for each stored mapping.
4. `stringField` at `RunnerServer.java:3166` emits `Enter a value for executionId.` when the field is absent.
5. `AutomationMappingManager.link` at `AutomationMappingManager.java:39-65` creates records without `executionId`.
6. The observed sanitized HTTP response is 400: `{"error":"Enter a value for executionId."}`.

This is not a wrong route, missing query parameter, wrong method, serialization error, storage-root failure or fixture failure. The supported POST/store contract creates the record shape that the GET handler cannot consume.

The behavior fails closed and no cross-tenant disclosure was observed, but authorized mapping reads cannot be verified. H-005 remains open.
