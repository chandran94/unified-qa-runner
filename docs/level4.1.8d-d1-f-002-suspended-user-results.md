# D1-F-002 Suspended-User Results

## Test

`ScreenshotVideoStreamingTest.suspendedUserCannotDownloadScreenshotOrVideo`

The fixture creates a synthetic user with project membership, suspends the authoritative organization membership before the request, and uses a separate authenticated session. The test requests both the screenshot and video endpoints.

## Result

- Screenshot: HTTP 403, no screenshot bytes in the denial body.
- Video: HTTP 403, no video bytes in the denial body.
- Denial occurs before `streamFileResponse` according to the source ordering.
- Classification: VERIFIED within the isolated tested scope.

The denial response may contain a small JSON error message; the assertion correctly checks absence of media bytes rather than requiring an empty error body.

