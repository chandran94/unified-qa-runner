# D1-F-002 HTTP Audit

| Property | Source evidence | Status |
|---|---|---|
| Success status | `sendResponseHeaders(200, contentLength)` | SOURCE-REVIEWED |
| Screenshot content type | `image/png` | SOURCE-REVIEWED; historical test artifact available |
| Video content type | `video/mp4` or `video/webm` based on suffix | SOURCE-REVIEWED |
| Content length | `Files.size(file)` before response | SOURCE-REVIEWED |
| Cache behavior | `Cache-Control: no-store` | SOURCE-REVIEWED |
| Content-Disposition | Not set by these handlers | NOT APPLICABLE to current contract / no evidence required |
| Missing file | Regular-file check returns 404 path | Historical test artifact available |
| Unauthorized response | Authentication/access checks precede streaming | Historical evidence partial |
| Empty file | No dedicated test artifact located | NOT VERIFIED |
| HEAD behavior | Handlers explicitly require GET | SOURCE-REVIEWED; compatibility not separately tested |
| Range support | No new range behavior identified | SOURCE-REVIEWED |
| Client disconnect | No explicit dedicated handling or test | NOT VERIFIED |
| Post-header failure | No committed-response guard found in top-level error path | NOT VERIFIED |

The fixed-length response preserves the apparent existing response contract for ordinary stable files. A file changing between `Files.size` and the read can create a transfer error or length mismatch; no race test is available. No second response should be attempted after headers are committed, but the reviewed code does not make that condition explicit, so this remains an evidence gap.

