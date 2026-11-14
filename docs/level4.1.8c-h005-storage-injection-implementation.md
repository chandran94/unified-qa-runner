# H-005 Storage Injection Implementation

## Production files modified

- `src/main/java/com/example/unifiedqarunner/TestCaseManager.java:15` — instance `configPath`, default and `Path` constructors, and operational reads/writes use the instance path.
- `src/main/java/com/example/unifiedqarunner/AutomationMappingManager.java:18` — equivalent instance path injection and default delegation.
- `src/main/java/com/example/unifiedqarunner/RunnerServer.java:96` — both managers receive the same normalized `projectRoot/qa-runner.json`.

No authorization, RBAC, CSRF, JSON schema, migration, or production default was changed.

## Focused storage tests

`StorageRootInjectionTest` contains 3 tests and passed 3/3:

- shared disposable document preserves sentinel and both sections;
- injected path does not fall back, null paths fail, and default path remains `qa-runner.json`;
- missing and malformed files retain existing manager semantics.

The tests use only temporary directories and never mutate the repository-root store.

## Validation

- `mvn -q -DskipTests compile`: PASS.
- `git diff --check`: PASS; Git emitted only existing line-ending normalization warnings.
