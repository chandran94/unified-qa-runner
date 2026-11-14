# Level 4.1.8D D2 ZIP Synthetic Test Results

## Test command

`mvn -Dtest=ZipImportResourceLimitsTest test`

Result: PASS, 5 tests, 0 failures, 0 errors, 0 skipped.

## Covered assertions

1. Valid import and exact entry/total boundary values succeed.
2. Entry-count and per-entry decompressed-byte violations are rejected and partial output is removed.
3. Aggregate decompressed bytes and archive input size are rejected without creating an import directory.
4. Excessive path depth, ZIP-Slip traversal, and duplicate normalized paths are rejected; no outside file is created.
5. Nested archives are not recursively extracted; malformed/empty input is rejected and the prior valid import remains the only registered import.

The fixtures are small deterministic `ZipOutputStream` archives under a unique temporary root. No ZIP bomb, external archive, live store, or customer data was used.

## Not independently exercised

Exact compression-ratio rejection, unknown/zero compressed-size metadata, and HTTP multipart rejection at a near-50 MiB boundary were not separately executed. The implementation does not claim exact ratio enforcement; this is an explicit residual limitation.
