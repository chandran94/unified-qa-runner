# D1-F-002 Endpoint Audit

## Reviewed handlers

Both handlers are in [RunnerServer.java](../src/main/java/com/example/unifiedqarunner/RunnerServer.java):

- `serveScreenshot` — `GET /screenshots/{file}`; current source approximately line 2349.
- `serveVideo` — `GET /videos/{file}`; current source approximately line 3452.
- `streamFileResponse` — shared response helper; current source approximately line 3473.

## Request-to-file flow

The top-level dispatch invokes `requireAuthenticatedUser` before the media handlers. Each handler then requires `GET`, extracts a file name, rejects `..`, `/`, and `\\`, resolves under the project `target/screenshots` or `target/videos` directory, normalizes the path, checks lexical root containment and `Files.isRegularFile`, then calls `requireArtifactAccess` before opening the file. `requireArtifactAccess` resolves the authoritative project assignment and delegates to tenant/project access resolution with `TEST_EXECUTION` permission.

Only after those checks does `streamFileResponse` call `Files.size`, open `Files.newInputStream`, send a 200 response, and copy the body.

## Buffering review

The shared helper allocates `new byte[16 * 1024]` and writes each read to the response output. No file-size-based byte array, `ByteArrayOutputStream`, or `Files.readAllBytes` occurs in these two paths.

Other `readAllBytes` occurrences found in `RunnerServer.java` are outside these media paths, including static HTML and process/error handling. They are not classified as D1-F-002 evidence.

## Containment limitation

The handlers use lexical `normalize()` and `startsWith`, but do not call `toRealPath()` or use no-follow link semantics before opening the file. `Files.isRegularFile` follows symlinks by default. A symlink placed inside a writable media root could therefore require separate D3/path-containment review. The existing focused tests do not cover this case.

## Classification

Streaming implementation: SOURCE-REVIEWED and supported by available historical test artifacts. Path-containment robustness: MATERIAL UNRESOLVED LIMITATION; not independently verified for symlink escape.

