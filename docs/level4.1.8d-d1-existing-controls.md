# Level 4.1.8D D1 — Existing Control Review

| Control | Status | Evidence |
|---|---|---|
| Authentication/session | IMPLEMENTED, prior evidence | `RunnerServer` route gate and prior Level 3/H-005 tests. |
| CSRF/same-origin | IMPLEMENTED, prior evidence | Mutation gates in `RunnerServer` around imports, artifacts and attachments. |
| Organization/project ownership | IMPLEMENTED for reviewed migrated resources | `ProjectOwnershipManager`, `TenantContextResolver`, `ownedProjectAccess` and `requireOwnedProjectAccess`. |
| Attachment parent ownership | IMPLEMENTED, evidence incomplete | Attachment route checks ATTACHMENT ownership; upload resolves story project and quarantines unresolved ownership. |
| Attachment size/type validation | IMPLEMENTED | `MAX_ATTACHMENT_BYTES`, extension allowlist, image/PDF signatures, text NUL check. |
| Attachment storage naming | IMPLEMENTED | Generated UUID storage names and sanitized original names. |
| Report real-path containment | IMPLEMENTED and previously tested | `ReportStorageSecurity.resolveRegularReportFile`; streams report and sets CSP/no-store. |
| Screenshot/video lexical containment | PARTIALLY IMPLEMENTED | Normalize/root-prefix/regular-file checks; no real-path symlink containment. |
| Screenshot/video list filtering | IMPLEMENTED within prior evidence | `artifactVisible` applied before serialization. |
| Screenshot/video streaming | PARTIALLY IMPLEMENTED | Uses `readAllBytes`; no general size bound. |
| ZIP Slip normalization | IMPLEMENTED within checked path | `RemoteImportConfig.extractZipFile` rejects normalized paths outside target root. |
| ZIP resource limits | MISSING | No uncompressed-size, entry-count, nesting or ratio limits. |
| GitHub URL validation | PARTIALLY IMPLEMENTED | Prefix allowlist; no comprehensive egress/redirect policy. |
| Temporary cleanup | IMPLEMENTED on normal path | ZIP temp and attachment temp are deleted in finally blocks; crash cleanup unverified. |
| Secret redaction | IMPLEMENTED in prior C-002 evidence, D1 not re-executed | Preserve prior regression evidence. |
| Audit logging | PARTIALLY IMPLEMENTED | User-story activity and organization audit exist; file access/deletion coverage incomplete. |
| Quarantine of unresolved ownership | IMPLEMENTED within reviewed ownership model | `ProjectOwnershipManager.quarantine`; complete file-family coverage remains unverified. |

