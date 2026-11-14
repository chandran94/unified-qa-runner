# D1-F-001 ZIP API and Processing Trace

## User-controlled import path

`POST /api/imports/zip` is registered in [RunnerServer.java](../src/main/java/com/example/unifiedqarunner/RunnerServer.java) and handled by `importFromZip` (current source around lines 1635-1697).

Trace:

```text
HTTP multipart request
  -> projectId query is checked by requireProjectAccess(TEST_EXECUTION, write)
  -> existing bounded request read (50 MiB + 1 byte) and Content-Length precheck
  -> temporary ZIP under projectRoot/target/uploads
  -> RemoteImportConfig.extractZipFile
  -> ZipInputStream over Files.newInputStream(zipFile)
  -> entry enumeration and normalized ZIP-Slip check
  -> entry/path/actual decompressed-byte counters
  -> output below importsDir
  -> import metadata save to qa-runner.json
  -> project ownership assignment
  -> temporary upload deletion in finally
```

`extractZipFile` is in [RemoteImportConfig.java](../src/main/java/com/example/unifiedqarunner/RemoteImportConfig.java), current source around lines 335-442. It uses `ZipInputStream`, reads each entry into an 8 KiB buffer, counts actual bytes, writes under the UUID-scoped imports directory, and deletes the partial target on failure.

## Current controls

- Archive input size: `ZipImportLimits.maxArchiveBytes`; direct file-size check and route-level bounded body handling.
- Entry count: counts files and directories.
- Decompressed bytes: per-entry and total overflow-safe counters.
- Path depth and duplicate normalized path checks.
- Existing normalized ZIP-Slip containment.
- Nested ZIPs are files; no recursive extraction.
- Registration occurs after extraction completes.
- Partial target cleanup is attempted in `finally`.

## Compression accounting

The source does not call `ZipFile`, `ZipEntry.getCompressedSize()`, or a compressed-payload counter. It only observes decompressed bytes returned by `ZipInputStream.read`. `ZipEntry` metadata is not used for ratio checks. The current path therefore has no exact compression-ratio enforcement.

## Other ZIP path

`RunnerServer.extractZip` (current source around lines 4041-4046) uses the JDK ZIP filesystem provider for Maven, Node.js, and JMeter tool installation. These are trusted tool-download/install flows, not the user ZIP import route. They remain a separate review surface and were not changed or tested in this assessment.

## Related evidence

The focused test class is [ZipImportResourceLimitsTest.java](../src/test/java/com/example/unifiedqarunner/ZipImportResourceLimitsTest.java). The tenant/path regression is [RemoteImportTenantIsolationTest.java](../src/test/java/com/example/unifiedqarunner/RemoteImportTenantIsolationTest.java). This report does not independently rerun either test.

Confidence: HIGH for implemented source paths; HIGH that exact ratio is absent from the inspected code.
