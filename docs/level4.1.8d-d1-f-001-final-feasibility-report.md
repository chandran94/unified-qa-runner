# D1-F-001 ZIP Compression-Ratio Feasibility Final Report

## Executive conclusion

The current user ZIP import path is materially bounded against decompressed-size and entry-count exhaustion, but exact compression-ratio enforcement is not available from the current public JDK streaming usage. A generic outer counting stream would count archive bytes and cannot establish per-entry compressed payload boundaries. `ZipFile` metadata can support early screening, but metadata is not independently observed compressed-byte accounting.

**Recommendation:** keep the current hard limits and preserve the existing path/tenant controls; do not implement a hand-written parser or speculative ratio check. If exact ratio is mandatory for D1-F-001 closure, obtain separate approval to evaluate a maintained ZIP parser/library with proven per-entry accounting and compatibility/security evidence. Alternatively, obtain explicit risk acceptance for the residual ratio limitation. D1-F-001 remains OPEN.

## Existing processing flow

The implemented user route is `POST /api/imports/zip` → `RunnerServer.importFromZip` → bounded temporary ZIP → `RemoteImportConfig.extractZipFile` → JDK `ZipInputStream` → actual byte counters/path checks → trusted imports directory → metadata save and ownership assignment. The separate private tool-installer ZIP filesystem path is outside the confirmed user-import finding and remains separately reviewable.

## Answers to required questions

- `ZipFile` can enumerate entries and expose compressed/uncompressed metadata, but it does not provide exact independently observed compressed payload bytes for a trusted ratio. Classification: metadata-based early rejection/hybrid only.
- Current `ZipInputStream` permits accurate decompressed-byte counting but not exact per-entry compressed-byte accounting through a generic wrapper. Read-ahead, headers, descriptors, ZIP64, and malformed inputs prevent a defensible hand-rolled boundary calculation.
- No suitable archive library is declared in local `pom.xml`. Any new library is a separate dependency/security approval and was not fetched or evaluated.
- The preferred current action is no further implementation in this phase: preserve actual-byte limits and document the closure blocker. This is the smallest safe choice and avoids compatibility and parser-security regression.

## Evidence and execution status

Source and tests were inspected read-only. Maven, tests, compilation, Java execution, HTTP, external integrations, archive extraction, and proof-of-concept attacks were **NOT RUN** by instruction. The prior D2 test results are reported historical evidence only.

## State preservation

- H-005: CLOSED, unchanged.
- D1-F-001: OPEN; no finding-register update.
- D1-F-002: OPEN/UNREMEDIATED.
- D3-D5: NOT STARTED.
- Production readiness: NOT APPROVED.
- Port 8081: untouched.
- No commit or push.
- No Java source, test source, `pom.xml`, or existing report was modified in this phase.
