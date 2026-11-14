# D1-F-002 Symlink and Real-Path Results

## Test

`ScreenshotVideoStreamingTest.symlinkOutsideTrustedRootIsRejectedWhenWindowsAllowsSymlinkCreation`

The test attempts to create a symlink under the disposable screenshot root pointing to a disposable outside file. It requires no elevation and uses JUnit’s capability assumption.

## Result

- Test status: SKIPPED (1 skipped in the focused class).
- Reason: Windows symlink creation was unavailable under the current non-elevated test environment.
- No symlink escape PASS is claimed.

## Source limitation

`RunnerServer.serveScreenshot` and `serveVideo` use normalized lexical `startsWith` checks and `Files.isRegularFile`. The reviewed path does not establish real-path containment or no-follow-link semantics. `Files.isRegularFile` follows symlinks by default. This is a material unresolved path-containment concern requiring separate remediation/disposition; it was not changed in this phase.

Classification: BLOCKED for runtime verification; SOURCE-REVIEWED concern remains OPEN.

