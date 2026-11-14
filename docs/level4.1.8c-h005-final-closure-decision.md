# Level 4.1.8C H-005 Final Closure Decision

## Decision

**BLOCKED — INSUFFICIENT EVIDENCE**

The last test/mapping CRUD family cannot be verified safely with the current no-code test harness. This is a storage-isolation blocker, not a security PASS and not a confirmed vulnerability.

## Minimal approval-required proposal

1. Add a narrowly scoped `TestCaseManager(Path configPath)` constructor and replace direct `Path.of("qa-runner.json")` references with the injected path.
2. Add a narrowly scoped `AutomationMappingManager(Path configPath)` constructor and replace its direct relative-path references.
3. Update only `RunnerServer` construction to pass `projectRoot.resolve("qa-runner.json")`.
4. Preserve no-argument constructors only if required for existing production callers, with the current working-directory default unchanged.
5. Do not alter authorization, ownership, schema semantics, or production defaults.
6. Add focused path-injection tests proving all reads/writes remain under a disposable root and the current project file is not opened.
7. Then run the two-organization CRUD HTTP matrix with persisted-state assertions.

## Risks and required approval

The change affects compatibility-store path resolution and may expose additional hidden callers of the no-argument managers. Before implementation, approve a separate production-code testability change and inventory all constructors/callers. No live data migration is required, but existing default behavior must remain unchanged for the normal application root.

H-005 tracking remains OPEN. Production readiness remains NOT APPROVED.

