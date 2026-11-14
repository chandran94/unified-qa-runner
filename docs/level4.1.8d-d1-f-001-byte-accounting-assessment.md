# D1-F-001 Compressed-Byte Accounting Assessment

## Why the current stream is insufficient

The current [RemoteImportConfig.extractZipFile](../src/main/java/com/example/unifiedqarunner/RemoteImportConfig.java) reads through `ZipInputStream`. The application can accurately count bytes returned after decompression, which is the basis of the current per-entry and total limits. The outer source stream contains the local header, compressed payload, optional data descriptor, later entries, central directory, and end records.

A generic `CountingInputStream` around the ZIP file would count total archive bytes, not the compressed payload for the current entry. ZIP parser buffering/read-ahead can consume bytes beyond the logical entry boundary. Local headers may omit sizes when data descriptors are used. ZIP64 and malformed records further complicate manual boundary calculation. Therefore such a counter would not prove an exact per-entry ratio.

## Required distinctions

- Total archive bytes: bounded staged input size.
- Compressed payload bytes per entry: not independently observed by the current API.
- Decompressed bytes per entry: observed from `ZipInputStream.read` and currently bounded.
- Archive overhead: headers, descriptors, central directory, and end records; not part of a per-entry compressed payload ratio.
- Metadata sizes: available only when the archive supplies them and not independently trusted.

## Feasibility answers

| Question | Assessment |
|---|---|
| Exact per-entry raw compressed bytes from current public streaming API? | Not established. |
| Exclude headers/descriptors exactly with a generic wrapper? | No. |
| Incremental exact ratio threshold before excessive decompression? | Not safely with current public API. |
| Parser internals/manual ZIP parser required? | Likely, unless a maintained library provides the needed accounting. |
| Memory bounded? | Possible, but correctness/compatibility risk increases. |
| Disk bounded? | Possible while retaining current actual-byte and staged-input caps. |
| Compatibility risk? | Material for ZIP64, data descriptors, encrypted/unsupported methods, malformed archives, and unusual producers. |

## Assessment

Do not implement a hand-rolled raw-byte counter or parser solely to claim ratio enforcement. Preserve the current hard actual-byte limits. Exact ratio requires a separately selected and tested parser/library contract.

Confidence: HIGH for the distinction between outer archive counting and per-entry payload counting; MEDIUM for edge-case behavior not executed here.
