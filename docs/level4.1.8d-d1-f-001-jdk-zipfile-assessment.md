# D1-F-001 JDK ZipFile Assessment

## Static API assessment

`java.util.zip.ZipFile` can open a staged regular ZIP file, enumerate the central-directory entries without extracting them, expose `ZipEntry.getCompressedSize()` and `ZipEntry.getSize()` when the archive provides those values, and provide an input stream for actual decompressed-byte counting. This assessment did not execute Java or fetch JDK sources; these points are API-level technical assessment, not runtime evidence.

## Security meaning of metadata

Central-directory compressed and uncompressed sizes are archive metadata. They are useful for early rejection and planning, but they are not independently observed compressed payload bytes. Values may be unknown (`-1`), inconsistent with the actual stream, affected by ZIP64/data-descriptor representation, or associated with malformed input. They must never replace actual decompressed-byte counters.

## Capabilities

| Question | Assessment |
|---|---|
| Safely stage bounded input? | Yes, current route already stages a bounded temporary ZIP. |
| Enumerate before extraction? | Yes, `ZipFile` supports entry enumeration. |
| Read compressed/uncompressed sizes? | Yes when metadata is present; unknown values are possible. |
| Count actual decompressed bytes? | Yes, by counting bytes from each entry stream. |
| Detect malformed archives? | Generally yes, but exact behavior must be tested for accepted ZIP variants. |
| Exact trustworthy ratio? | No. Metadata ratio is not observed compressed-payload accounting. |
| New dependency? | No, JDK API. |
| Memory impact? | Bounded by staged file and streaming entry reads; central-directory memory still depends on archive metadata. |
| Path containment impact? | None if existing normalized and real-path checks are retained. |
| Compatibility impact? | Potentially different handling of malformed, ZIP64, data-descriptor, encrypted, or unusual ZIPs; requires compatibility tests. |

## Classification

`ZipFile` is a **hybrid/metadata-based early-screening option**, not exact verified ratio enforcement. It can reject obviously excessive declared ratios before extraction while current actual-byte limits remain authoritative. It does not alone make D1-F-001 closure-eligible.

Confidence: MEDIUM because this is a static API assessment without execution or local JDK-source inspection.
