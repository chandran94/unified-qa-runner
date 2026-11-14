# H-005 Mapping Contract Tests

Command: `mvn -q "-Dtest=StorageRootInjectionTest,FinalH005CrudHttpVerificationTest" test`

Result: **5/5 PASS**.

- `StorageRootInjectionTest`: 3/3, including optional `executionId` compatibility and shared JSON preservation.
- `FinalH005CrudHttpVerificationTest`: 2/2, including isolated two-organization mapping HTTP verification.

Also: `mvn -q -DskipTests compile` PASS; `git diff --check` PASS with only existing CRLF normalization warnings.
