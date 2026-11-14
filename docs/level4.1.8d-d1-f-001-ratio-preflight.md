# D1-F-001 Compression-Ratio Feasibility Preflight

## Scope and restrictions

This is a read-only feasibility assessment. No Java, test, `pom.xml`, findings-register, or historical report files were modified. Maven, tests, Java execution, HTTP, external imports, and archive extraction were not run.

## Evidence reviewed

- [RemoteImportConfig.java](../src/main/java/com/example/unifiedqarunner/RemoteImportConfig.java), including `ZipImportLimits` and `extractZipFile`.
- [RunnerServer.java](../src/main/java/com/example/unifiedqarunner/RunnerServer.java), including `importFromZip` and the separate private tool-installer `extractZip`.
- [ZipImportResourceLimitsTest.java](../src/test/java/com/example/unifiedqarunner/ZipImportResourceLimitsTest.java).
- [RemoteImportTenantIsolationTest.java](../src/test/java/com/example/unifiedqarunner/RemoteImportTenantIsolationTest.java).
- [D2 ZIP implementation report](./level4.1.8d-d2-zip-implementation.md).
- [D2 ZIP policy](./level4.1.8d-d2-zip-limit-policy.md).
- [D2 closure assessment](./level4.1.8d-d2-zip-finding-closure-assessment.md).
- [D1 final audit](./level4.1.8d-d1-final-audit-report.md).
- [Security Findings Register](./security/security-findings-register.md).
- [pom.xml](../pom.xml).

## Current tracking reconciliation

The register confirms H-005 is CLOSED within its verified scope. D1-F-001 is recorded in the D2 reports as not eligible for unconditional closure because exact ratio enforcement was not claimed. D1-F-002 remains out of scope/open. Production readiness remains NOT APPROVED.

Current worktree changes predate this assessment and include unrelated application/test changes plus untracked audit evidence. They were preserved. No source or test change was made for this assessment.

## Initial conclusion

The current actual-byte limits materially reduce decompression-bomb impact, but neither `ZipInputStream` nor a generic outer byte counter proves exact per-entry compressed payload size. A `ZipFile` metadata check can be useful as early screening, not as an exact trusted ratio control. A closure-grade ratio control requires a separately approved parser/library approach or explicit risk acceptance.

Confidence: HIGH for the local source trace; MEDIUM for library alternatives not present in the repository because no dependency was fetched or executed.
