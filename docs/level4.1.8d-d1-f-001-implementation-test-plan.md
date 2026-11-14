# D1-F-001 Future Implementation Test Plan

This is a design-only plan. No tests were run in this feasibility phase.

## Fixture and isolation requirements

- Use a unique disposable temporary root and synthetic ZIPs only.
- Stage input below the test root; never use project-root `qa-runner.json`, live imports, port 8081, or external URLs.
- Use low injected thresholds so fixtures remain small.
- Assert output, registration, cleanup, and unchanged unrelated files.
- Run sequentially with bounded timeouts and one Maven process.

## Required cases

| Case | Fixture | Expected result |
|---|---|---|
| R01 | Small valid ZIP | Import succeeds. |
| R02/R03 | Ratio just below/above threshold | Below succeeds; above rejects if exact accounting is supported. |
| R04/R05 | Unknown/zero compressed-size metadata | Safe fail/continue according to documented parser contract; never divide by zero or trust unknown metadata. |
| R06 | Incorrect size metadata | Actual-byte policy remains authoritative; no unsafe acceptance. |
| R07 | ZIP64 metadata | Documented parser behavior; bounded and compatible. |
| R08 | Data descriptor entry | Per-entry compressed/decompressed accounting remains correct. |
| R09 | Multiple entries | Per-entry and aggregate limits are independent and cumulative. |
| R10 | Duplicate paths | Reject without overwrite or partial registration. |
| R11 | Malformed ZIP | Reject and clean partial output. |
| R12 | Entry/path traversal | Reject; no outside-root file. |
| R13 | Symlink-containing archive where supported | Reject or preserve documented safe behavior; real-path containment verified. |
| R14 | Tenant A/B import fixtures | Ownership and catalog filtering unchanged. |
| R15 | Existing valid import shape | JSON and authorized workflow remain compatible. |

## Additional assertions

Measure no exact ratio claim unless the selected parser provides documented, tested per-entry compressed-payload semantics. Assert bounded memory/disk behavior indirectly through configured low thresholds and output accounting, not by creating a ZIP bomb.
