# H-005 Storage Injection Design

## Scope

This approved change addresses only the blocked test/mapping CRUD verification family. The existing `qa-runner.json` default remains unchanged; isolated instances receive an explicit `Path`.

## Design

- `TestCaseManager()` delegates to `Path.of("qa-runner.json")`.
- `AutomationMappingManager()` delegates to the same compatibility default.
- Each manager stores a normalized, absolute instance path.
- `RunnerServer(Path projectRoot, ...)` constructs both managers against `projectRoot/qa-runner.json`.
- No `user.dir` mutation, environment-variable override, schema change, migration, or global mutable storage setting is introduced.
- Both managers read the same JSON object and replace only their own top-level section, preserving unrelated sections. Tests exercise sequential writes; no new cross-instance locking or atomicity redesign is introduced.

## Safety boundary

The HTTP fixture uses a unique temporary project root and ephemeral loopback port. All stores reachable by the test server are rooted in that temporary directory. The live working-directory `qa-runner.json` and port 8081 are not used.

## Known limitation

The existing mapping-list route authorizes through `executionId`, while newly created mapping records do not contain that field. This is an existing route/data-contract mismatch, not a storage-root issue; it remains unresolved and is reported as a verification blocker.
