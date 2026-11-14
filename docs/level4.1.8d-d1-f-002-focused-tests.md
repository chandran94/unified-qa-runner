# D1-F-002 Focused Test Results

## Isolation

[ScreenshotVideoStreamingTest.java](../src/test/java/com/example/unifiedqarunner/ScreenshotVideoStreamingTest.java) creates a unique temporary root, synthetic users/projects, synthetic screenshot/video files and an ephemeral `RunnerServer` port `0`. Ownership metadata is disposable and cleanup is limited to the owned root. No external service or port 8081 is used.

## Executed scenarios

Command:

```powershell
$env:MAVEN_OPTS='-Xmx512m -XX:MaxMetaspaceSize=192m'
mvn -Dtest=ScreenshotVideoStreamingTest test
```

Result: **3/3 PASS**, 0 failures, 0 errors, 0 skipped.

Coverage includes:

- Authorized screenshot body, content type, cache header and content length.
- Authorized video body, content type and content length.
- A deterministic file larger than the 16 KiB transfer buffer.
- Cross-tenant video denial before content delivery.
- Missing-file handling.
- Unauthenticated denial.
- Traversal rejection.

The test does not claim a heap benchmark or client-disconnect proof. Structural source inspection supplies the bounded-buffer and deterministic-close evidence.

