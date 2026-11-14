# D1-F-001 Parser API Verification

## Current parser

[RemoteImportConfig.extractZipFile](../src/main/java/com/example/unifiedqarunner/RemoteImportConfig.java) uses `java.util.zip.ZipInputStream` over `Files.newInputStream(zipFile)`. The code counts actual bytes returned by `ZipInputStream.read`, but no compressed-payload counter is present. A generic counter around the outer archive stream would include headers, descriptors, other entries, central-directory data, and possible parser read-ahead; it cannot prove a per-entry ratio.

## JDK `ZipFile`

Static API knowledge indicates `java.util.zip.ZipFile` can enumerate entries and expose `ZipEntry.getCompressedSize()` / `getSize()` metadata, then stream decompressed data. The metadata may be unknown or inconsistent for malformed/unusual inputs and is not independent observation of compressed payload bytes. Therefore `ZipFile` is classified as metadata-based early screening plus actual-byte enforcement, not exact verified ratio enforcement.

This assessment did not execute Java or inspect JDK implementation internals, so edge-case claims for ZIP64, data descriptors, encryption, unsupported methods, and malformed records are **NOT VERIFIED** locally.

## Apache Commons Compress candidate

Local cache paths were inspected read-only:

- `C:\Users\elayachandran\.m2\repository\org\apache\commons\commons-compress\1.28.0\` contains a POM but no JAR or source artifact.
- Cached `1.26.2` contains a JAR but no local source artifact.
- Cached `1.21` contains a JAR and source JAR, but the project does not declare it and no API/source extraction or runtime inspection was performed because this phase prohibits archive extraction and Java execution.

No trustworthy local evidence was established for a class/method whose returned value is actual per-entry compressed payload bytes, whether the count is incremental, or whether it correctly handles ZIP64/data descriptors and all supported methods.

## Mandatory gate conclusion

**NOT VERIFIED.** The required counter semantics and supported-format behavior remain unproven. No library was selected, no version was approved, and no production or test code may be changed under this gate.
