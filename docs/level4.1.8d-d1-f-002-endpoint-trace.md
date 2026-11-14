# D1-F-002 Endpoint Trace

## Screenshot

Route dispatch in [RunnerServer.java](../src/main/java/com/example/unifiedqarunner/RunnerServer.java) sends `/screenshots/` requests through `requireAuthenticatedUser` and `serveScreenshot`.

`serveScreenshot` performs, in order:

1. GET-method validation.
2. Filename traversal/separator rejection.
3. `target/screenshots` normalized containment and regular-file check.
4. `requireArtifactAccess("SCREENSHOT", fileName, TEST_EXECUTION, false)`.
5. Content type `image/png` and `Cache-Control: no-store`.
6. Fixed-length streaming response.

## Video

`/videos/{file}` follows the same sequence through `serveVideo`, with `video/webm` for `.webm` and `video/mp4` otherwise. Authorization and path checks remain before file opening.

## Changed transfer behavior

Both handlers now call `streamFileResponse`. The helper obtains `Files.size`, opens `Files.newInputStream`, sends the existing HTTP 200 response length, and copies through a fixed 16 KiB byte buffer. It closes the input and response output streams deterministically.

No new route, header, status code, storage path, ownership rule or response contract was introduced.

