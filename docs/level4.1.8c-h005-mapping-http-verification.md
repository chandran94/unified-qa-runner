# H-005 Mapping HTTP Verification

The prior isolation gate passed with a unique temporary root, injected `qa-runner.json`, separate OrgA/OrgB sessions, an ephemeral loopback port and no port 8081 access.

- Mapping creation: OrgA `POST /api/testcase-automation-mappings` with `testCaseId`, `assetId` and `confirmShared`; HTTP 201.
- Authorized listing: OrgA `GET /api/testcase-automation-mappings?testCaseId=<idA>`; expected 200, actual 400.
- Sanitized response: `{"error":"Enter a value for executionId."}`.
- Handler reached: `RunnerServer` mapping GET branch.
- No request body or missing CSRF was involved.

Status: MAP01 PASS; MAP02 FAIL due the production contract defect; mapping update NOT IMPLEMENTED; remaining negative matrix NOT RUN after the confirmed defect, per the phase boundary. No production or test code was changed during diagnosis.
