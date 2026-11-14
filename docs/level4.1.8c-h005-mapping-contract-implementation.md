# H-005 Mapping Contract Implementation

Modified `src/main/java/com/example/unifiedqarunner/RunnerServer.java` in the mapping-list handler. Each mapping is now authorized using its persisted `testCaseId` and the existing `ownedProjectAccess` helper with `TeamAuthorization.TEST_CASES`.

Authentication, CSRF, same-origin, project ownership, RBAC, JSON storage and the injected storage-path behavior remain unchanged. Mappings without `executionId` remain valid; present values are preserved; unassigned mappings fail closed.
