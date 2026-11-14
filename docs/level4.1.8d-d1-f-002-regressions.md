# D1-F-002 Regression Results

## Existing targeted regression

Command:

```powershell
$env:MAVEN_OPTS='-Xmx512m -XX:MaxMetaspaceSize=192m'
mvn -Dtest=TenantRouteIntegrationTest test
```

Result: **8/8 PASS**, 0 failures, 0 errors, 0 skipped.

This class exercises disposable tenant/project fixtures, screenshot ownership filtering, CSRF-protected bulk deletion, legacy/unassigned artifact handling and cross-tenant boundaries. It does not access port 8081.

## Compile

Command:

```powershell
mvn -DskipTests compile
```

Result: **BUILD SUCCESS**.

No full Maven suite and no `mvn clean` were run. The existing `LiveTwoAccountAuthorizationTest` was not run because it is browser/live-style coverage and browser execution was outside this phase.

