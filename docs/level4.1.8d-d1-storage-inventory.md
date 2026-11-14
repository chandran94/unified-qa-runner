# Level 4.1.8D D1 — Storage and Filesystem Inventory

Counted logical stores: **11**. Paths are reported relative to the configured project root; user-specific absolute paths are intentionally omitted.

| # | Store/root | Creation/write | Read/delete | Ownership and controls | Confidence |
|---:|---|---|---|---|---|
| 1 | `qa-runner.json` | Managers/config stores | JSON-backed CRUD | Project/org ownership indexes are additive; legacy records quarantine | High |
| 2 | `.qa-attachments` | `uploadUserStoryAttachment` around 5003 | Serve/delete helpers around 5053 | Generated storage names, extension/content checks, 10 MB cap, parent ownership | High |
| 3 | `target/screenshots` | `ExecutionService`/run artifacts | list/serve/delete screenshot methods | Artifact visibility and ownership; lexical path checks; symlink behavior unverified | High |
| 4 | `target/videos` | `ExecutionService`/run artifacts | list/serve/delete video methods | Artifact visibility and ownership; lexical path checks; whole-file reads | High |
| 5 | `target/runner-reports` | `ExecutionService` run directories | `serveReport` | `ReportStorageSecurity` real-path containment and regular-file check; streaming | High |
| 6 | `target/uploads` | ZIP import temporary file | Deleted in `finally` | 50 MB compressed/request bound; decompressed resource limits absent | High |
| 7 | `imports/` | `RemoteImportConfig` GitHub/ZIP imports | scan/catalog/delete | Real-path trusted-root resolution; ZIP entry lexical containment; size/entry limits incomplete | High |
| 8 | Configured shared-path directories | `SharedPathConfig` stores user path | scan/catalog/discovery | Project ownership is indexed; arbitrary configured roots and path disclosure need D3 review | High |
| 9 | Tool-install directories (`apache-maven`, `nodejs`, JMeter) | Runner download/install helpers | Process execution and cleanup | Platform-level paths; downloaded archives extracted by generic helper | Medium |
| 10 | In-memory run logs | `RunEntry.logs` | Run list/detail response | Run ownership gate; no durable file store; redaction/restart evidence limited | High |
| 11 | Plugin-discovered asset paths | `ExecutionService.pluginRegistry` | Catalog/scan/run selection | Local filesystem discovery; ownership derived through configured import/shared path | Medium |

No implemented plugin output store, webhook store, CI/CD artifact store, wiki attachment store, or durable queue store was confirmed. Wiki attachments are not separate from the implemented user-story attachment route in the inspected source.

