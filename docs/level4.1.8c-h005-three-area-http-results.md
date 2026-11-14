# Level 4.1.8C H-005 Three-Area HTTP Results

## Test class

[FinalH005HttpVerificationTest](../src/test/java/com/example/unifiedqarunner/FinalH005HttpVerificationTest.java) is test-only.

## Run/report object IDs — PASS within tested scope

Routes tested:

- `GET /api/runs`
- `GET /api/runs/{id}`
- `GET /api/runs/{id}/report`

Organization A and B received separate synthetic run/report objects. A saw only its own list entry, could read its own run and report, and B received `403` for A’s run and report. Unauthenticated run access returned `401`. Foreign response bodies did not disclose the fixture ID or filesystem path.

Result: **PASS for the implemented run/report object and file-serving operations exercised**.

## Test/mapping CRUD — BLOCKED

Routes identified:

- `GET/POST/PUT/DELETE /api/testcases*`
- `GET/POST/DELETE /api/testcase-automation-mappings`

The relevant managers use `Path.of("qa-runner.json")` rather than the isolated server root. Executing mutating HTTP tests could read or change the project’s existing working-directory store. No unsafe test was run; no test-only production bypass was added.

Result: **BLOCKED — disposable-store isolation unavailable without production/configuration changes**.

## Wiki mutations — PASS within tested scope

Routes tested:

- `POST /api/wiki/spaces`
- `GET /api/wiki/spaces`, `GET /api/wiki/spaces/{id}`
- `POST /api/wiki/pages`
- `PUT /api/wiki/spaces/{id}`
- `PUT /api/wiki/pages/{id}`
- `POST /api/wiki/pages/{id}/archive`

Same-tenant creation/update/archive succeeded. Cross-tenant reads, forged parent creation, cross-tenant space/page updates, cross-tenant archive, and missing-CSRF mutation were denied with the existing contract. Organization A content remained intact after B’s denied mutations.

Result: **PASS for implemented wiki operations exercised**.

## Focused result

Safe focused tests: **2/2 PASS**. Test/mapping CRUD: **NOT RUN / BLOCKED**, not counted as a pass.

