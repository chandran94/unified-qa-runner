# Level 4.1.8D D2 ZIP Regression Results

## Targeted command

`mvn "-Dtest=ZipImportResourceLimitsTest,RemoteImportTenantIsolationTest" test`

Result: PASS, 7 tests, 0 failures, 0 errors, 0 skipped.

- `RemoteImportTenantIsolationTest`: 2/2 PASS. Existing catalog/import ownership filtering and trusted-root/path redaction remain covered.
- `ZipImportResourceLimitsTest`: 5/5 PASS. Resource bounds and cleanup remain covered.

## Compile command

`mvn -DskipTests compile`

Result: PASS. Java 17 compilation completed; no full test suite and no `mvn clean` were run.

## Safety observations

No live server was started or restarted. No external integration was executed. No PostgreSQL, Docker, browser automation, or port 8081 access was used. Fixtures cleaned only task-owned temporary roots.

Prior broader H-005 and D1 test results are historical evidence and were not re-counted in this focused batch.
