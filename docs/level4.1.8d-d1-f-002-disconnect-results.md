# D1-F-002 Client-Disconnect Results

## Test

`ScreenshotVideoStreamingTest.clientDisconnectDoesNotPreventSubsequentDownload`

The test uses an isolated ephemeral HTTP server, requests the authorized video with a real session cookie, reads one byte from an input-stream response, closes the client body, and then performs a second complete download.

## Result

- Final focused command: `mvn -Dtest=ScreenshotVideoStreamingTest test`
- Result: 7 tests, 6 passed, 0 failed, 0 errors, 1 skipped.
- Client interruption scenario: PASS for bounded client-side interruption and subsequent request recovery.

## Limitation

The test cannot directly observe server-side stream closure or prove that no worker remains without a production test seam. The source uses try-with-resources for both input and response output, which is source evidence, but direct cleanup remains PARTIALLY VERIFIED rather than fully proven.

