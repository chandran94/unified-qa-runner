# H-005 Automation Mapping Route Inventory

| Method | Route | Handler | Inputs | Store |
|---|---|---|---|---|
| GET | `/api/testcase-automation-mappings` | `RunnerServer.java:523` | Optional `testCaseId`, `assetId` query filters | `AutomationMappingManager.list()` |
| POST | `/api/testcase-automation-mappings` | `RunnerServer.java:538` | JSON `testCaseId`, `assetId`, optional `confirmShared` | `link(...)` and ownership assignment |
| DELETE | `/api/testcase-automation-mappings` | `RunnerServer.java:558` | Query `testCaseId`, `assetId` | `unlink(...)` |
| PUT/PATCH | Not implemented | None | None | None |

The GET route requires authentication through the protected API path and filters by the optional query parameters. It does not read an `executionId` query parameter. POST and DELETE require same-origin validation, authentication, project ownership and TEST_CASES authorization.

At `RunnerServer.java:527`, GET calls `ownedProjectAccess(exchange, "AUTOMATION_EXECUTION", stringField(item, "executionId"), ...)`. `AutomationMappingManager.link` at `AutomationMappingManager.java:39-65` persists mapping fields but does not persist `executionId`.
