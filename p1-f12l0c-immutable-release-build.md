# P1-F12L.0C — Immutable Release Build

Status: ISOLATED BUILD VERIFIED. Production readiness: NOT APPROVED.

## Release evidence

- Release workspace: `C:\Users\elayachandran\AppData\Local\Temp\vscode-agent-codex-sandbox-JcxKmK\qa-runner-release-20261115-231044571`
- Immutable artifact: `unified-qa-runner-20261115-231141.jar`
- SHA-256: `34ae83da1b840da29209bb9daa3f6cd2eb1b39844a10bc2cea66f0e6e6dd8d09`
- Source revision: `4c273b99b237546bcec0b98506e47b39dd3400ec` (working-tree snapshot; no commit created)
- Runtime: Microsoft OpenJDK `25.0.5.0.2`; Maven `3.9.11`; build heap capped at approximately 512 MB.
- Build timestamp: `2026-11-15T23:11:41+05:30`.
- Entry point: `com.example.unifiedqarunner.Main`.
- Maven package: PASS; one Maven process; no real external runner execution.
- Manifest and `Main.class`: PASS. Checksum was generated from the versioned artifact and verified before launch.

## Isolation controls

The source snapshot excluded `target`, Git metadata, live attachment/backup directories, credentials/key material, and generated live data files. The final build wrote only to the isolated snapshot target directory. The live application's target/classes directory was not used or modified.

The release child was started with an isolated `qa.runner.dataDir`, a dynamically allocated loopback port, `QA_RUNNER_LOCAL_ONLY=true`, and `QA_RUNNER_ENV=development`. Secret-like inherited environment variables were removed from the child environment. Synthetic users and project data were bootstrapped only under the isolated storage root.

## Status vocabulary

- IMPLEMENTED: immutable shaded JAR and release manifest created.
- TEST VERIFIED: focused Maven set ran 6 tests with 6 PASS, 0 FAIL, 0 ERROR, 0 SKIPPED.
- LIVE VERIFIED: not claimed for the release artifact; the existing live process was only health-checked.
- D1-F-001 and D1-F-002: OPEN.
- Backup consistency: BLOCKED pending real external producer compliance.
