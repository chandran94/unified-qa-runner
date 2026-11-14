# H-005 Mapping Update Status

## Source review

The mapping route inventory in `RunnerServer.java` contains:

- `GET /api/testcase-automation-mappings`
- `POST /api/testcase-automation-mappings`
- `DELETE /api/testcase-automation-mappings`

No dedicated mapping `PUT` or `PATCH` handler was found. `AutomationMappingManager` exposes `link`, `unlink`, `list`, `listForTestCase`, and `listForAsset`; it exposes no update method. Existing PUT/PATCH routes in `RunnerServer` belong to other resources.

## Classification

**Mapping update: NOT IMPLEMENTED / NOT APPLICABLE as an implemented H-005 boundary.**

The supported workflow is delete-and-recreate, not in-place update. It is therefore excluded from the passing HTTP-test count and remains a future functionality/security requirement if introduced. Its absence does not create an unresolved implemented authorization gap.

## Verified implemented operations

Creation, listing, and deletion were exercised over isolated HTTP with separate Organization A and Organization B sessions. Cross-tenant reads and mutations were denied, forged relationships were denied, and persisted state was checked after deletion.

