# Level 4.1.8D D2 ZIP Preflight

## Scope

This remediation is limited to confirmed D1-F-001: resource exhaustion through the implemented user ZIP import path. D1-F-002 (unbounded screenshot/video reads) and all D3-D5 work remain out of scope.

## Source inventory

- HTTP entry point: `POST /api/imports/zip` in `src/main/java/com/example/unifiedqarunner/RunnerServer.java` (`importFromZip`). The route requires project access with `TEST_EXECUTION`, preserves the existing multipart and 50 MiB bounded request read, writes a task-owned temporary ZIP, invokes `RemoteImportConfig.extractZipFile`, assigns project ownership, and deletes the temporary ZIP in `finally`.
- Extraction path: `src/main/java/com/example/unifiedqarunner/RemoteImportConfig.java` (`extractZipFile`). It uses JDK `ZipInputStream` and extracts below the configured `imports` directory.
- Existing path control: normalized entry resolution and trusted-root real-path resolution in `RemoteImportConfig.resolveImportDirectory`.
- Separate trusted tool installer: private `RunnerServer.extractZip` is used for Maven/Node/JMeter tool installation. It is not the user-controlled ZIP import route and was not changed in this narrowly scoped batch.

## Existing gap

Before this batch, user ZIP extraction had ZIP-Slip normalization but no bounded entry count, actual decompressed per-entry limit, aggregate decompressed limit, path-depth limit, duplicate-path rejection, or failure cleanup. ZIP metadata was not sufficient for resource enforcement.

## Isolation gate

Focused tests use `Files.createTempDirectory`, synthetic `ZipOutputStream` fixtures, and a disposable `qa-runner.json` beneath the fixture root. No HTTP server, external integration, live database, port 8081, or project-root data is used. Cleanup walks only the test-created root.

## Decision

The approved narrow remediation is feasible without authentication, authorization, schema, or persistence redesign. Exact compression-ratio enforcement is not claimed because the current JDK `ZipInputStream` path does not provide reliable compressed-byte accounting for a safe exact ratio policy. This limitation is recorded as a residual D1-F-001 closure blocker.
