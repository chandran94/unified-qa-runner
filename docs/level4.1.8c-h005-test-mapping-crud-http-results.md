# H-005 Test/Mapping CRUD HTTP Results

## Test classes

- `StorageRootInjectionTest`: 3/3 PASS.
- `FinalH005CrudHttpVerificationTest#testCaseCrudIsTenantAndProjectScopedOverHttp`: 1/1 PASS.
- `FinalH005CrudHttpVerificationTest#mappingCrudUsesOwnedTestCaseAndCatalogAsset`: 0/1 PASS, 1 FAIL.

## Passing test-case evidence

Using separate authenticated sessions and disposable OrgA/OrgB projects:

- same-tenant create/read/update/delete succeeded;
- OrgA list excluded OrgB;
- OrgA direct read/update of OrgB returned 403;
- forged OrgB create returned 403;
- missing CSRF returned 403;
- unauthenticated mutation returned 401;
- authorized deletion succeeded.

## Mapping evidence

The synthetic catalog and authorized mapping creation reached the route. The subsequent authorized mapping-list request returned HTTP 400 with sanitized body `{"error":"Enter a value for executionId."}`; expected authorized read behavior is HTTP 200. This is an existing mapping route/data-shape mismatch: the route filters mappings using `AUTOMATION_EXECUTION` ownership and reads `executionId`, while `AutomationMappingManager.link` creates mappings without that field.

No production authorization code was changed to hide this failure. Mapping read verification is therefore unresolved, and H-005 closure is not recommended.

Mapping update is not implemented as an HTTP route (only GET/POST/DELETE are present in `RunnerServer.java:523-566`) and is not counted as a passed operation.

## Commands

- `mvn -q "-Dtest=StorageRootInjectionTest,FinalH005CrudHttpVerificationTest#testCaseCrudIsTenantAndProjectScopedOverHttp" test`: PASS, 4/4 unique tests.
- `mvn -q "-Dtest=FinalH005CrudHttpVerificationTest#mappingCrudUsesOwnedTestCaseAndCatalogAsset" test`: FAIL, 1/1 selected test; authorized mapping list received 400.
