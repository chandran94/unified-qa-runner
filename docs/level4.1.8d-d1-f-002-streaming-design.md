# D1-F-002 Streaming Design

## Design decision

Use a private `RunnerServer.streamFileResponse(HttpExchange, Path, String)` helper for only the screenshot and video download handlers.

- Buffer: fixed `16 * 1024` bytes.
- Input: `Files.newInputStream(file)` after authorization and containment checks.
- Output: `exchange.getResponseBody()`.
- Length: existing fixed-length response mode using `Files.size(file)`.
- Headers: existing content type and `Cache-Control: no-store` preserved.
- Cleanup: nested try-with-resources closes input and output.
- Client disconnect: I/O failure terminates the bounded copy and closes resources; no retry loop is introduced.

The design avoids a whole-file `byte[]`, `ByteArrayOutputStream`, per-download executor, cache or global lock. The per-transfer memory allocation is bounded by the fixed buffer; total memory under concurrent downloads remains dependent on the existing HTTP server concurrency model and is not benchmarked here.

## Security ordering

`requireArtifactAccess` remains before `streamFileResponse`, and `streamFileResponse` opens the file only after all existing authorization and path checks complete. No unauthorized response body is opened or copied.

