# Level 4.1.8D D2 ZIP Implementation

## Production files changed

- `src/main/java/com/example/unifiedqarunner/RemoteImportConfig.java`
  - Added immutable `ZipImportLimits` policy with conservative defaults and JVM-property overrides.
  - Added constructor-level policy injection for disposable focused tests; existing constructor behavior delegates to defaults.
  - Added archive input-size validation.
  - Replaced unbounded `Files.copy` extraction with buffered streaming and overflow-safe per-entry/aggregate byte accounting.
  - Added entry-count, path-depth, duplicate normalized-path, empty-archive, and partial-extraction cleanup controls.
  - Preserved ZIP-Slip normalized containment and non-recursive nested-archive behavior.
  - Existing trusted-root resolution remains fail-closed.
- `src/main/java/com/example/unifiedqarunner/RunnerServer.java`
  - Added an early safe `Content-Length` check to the existing bounded multipart ZIP upload path.

## Compatibility

The production `RemoteImportConfig(Path)` constructor remains available and uses the same project-root `imports` directory and `qa-runner.json` format. No schema, tenant authorization, project authorization, CSRF, RBAC, or ownership logic changed. No data migration occurred.

## Failure safety

An import entry is registered only after all extraction limits pass and configuration save succeeds. Failed extraction removes only its UUID-scoped target directory. Streams use try-with-resources. Error messages do not include absolute filesystem paths.

## Residual limitation

The current implementation does not claim exact compression-ratio enforcement. `ZipInputStream` does not expose a reliable, independent compressed-byte accounting stream for a safe exact ratio check. Adding a parser/dependency or broader archive redesign was not approved. D1-F-001 is therefore not recommended for unconditional closure until this limitation is separately accepted or addressed.
