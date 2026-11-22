# P1-F12L.0B.3 — Plugin Writer Safety

## Managed interface

The new `ArtifactPublicationService` is package-private and host-controlled. It accepts only paths below the current root’s staging and published report directories, rejects symbolic-link escapes and traversal, enforces bounded artifact count and bytes, and publishes under the existing `reports` write barrier. Staging is never publicly exposed by a new endpoint.

## Compatibility

Existing report file names and `RunResult.reportDirectory()` semantics are preserved for managed writers. Robot Framework preserves its external command contract while changing only its output directory to an isolated staging location before publication. No plugin SDK or API contract was rewritten.

## Tests

- `ArtifactPublicationServiceTest`: **3 PASS**.
- Existing report/media/persistence/security batch: **43 PASS, 0 FAIL, 0 ERROR, 1 SKIPPED**.
- Combined selected batch: **47 PASS, 0 FAIL, 0 ERROR, 1 SKIPPED**.
- Maven compile: **PASS**.
- `git diff --check`: **PASS**.

## Security status

No authentication, RBAC, CSRF, tenant, ZIP or media authorization control was weakened. D1-F-001 and D1-F-002 remain OPEN. External producer coverage is incomplete; therefore backup consistency and full recovery remain **BLOCKED**.
