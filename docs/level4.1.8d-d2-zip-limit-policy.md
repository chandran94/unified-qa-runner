# Level 4.1.8D D2 ZIP Limit Policy

Defaults are conservative and configurable through existing JVM system-property conventions. Invalid property values fall back to the documented defaults; non-positive effective values are rejected.

| Control | Default | Enforcement |
|---|---:|---|
| Archive input bytes | 50 MiB | Existing bounded upload read; early `Content-Length` rejection; direct extraction checks `Files.size` |
| ZIP entry count | 1,024 | Counts files and directories before processing |
| Decompressed bytes per file | 8 MiB | Actual bytes counted while streaming from `ZipInputStream` |
| Total decompressed bytes | 64 MiB | Aggregate actual bytes counted with overflow-safe arithmetic |
| Entry path depth | 32 components | Checked before extraction |
| Nested archive depth | 0 recursive extraction | Nested archives are retained as ordinary files; they are never recursively unpacked |
| Temporary extraction footprint | 64 MiB content budget | Bounded by total decompressed-byte limit; partial output is removed on failure |
| Processing duration | Not added | No unbounded retry or new worker was introduced; caller/server lifecycle remains unchanged |
| Compression ratio | Not claimed | Exact enforcement is unavailable with current JDK streaming API; actual byte and archive/entry budgets remain mandatory |

Supported properties are `qa.runner.zip.maxArchiveBytes`, `qa.runner.zip.maxEntries`, `qa.runner.zip.maxEntryBytes`, `qa.runner.zip.maxTotalBytes`, and `qa.runner.zip.maxPathDepth`. Tests inject low limits through the package-private constructor rather than changing production defaults.

The policy intentionally does not fabricate compressed sizes, recursively inspect nested archives, or weaken existing normalized path and trusted-root checks.
