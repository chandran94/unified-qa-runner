# D1-F-002 Preflight

## Scope

D1-F-002 concerns unbounded whole-file buffering in implemented screenshot/video download handlers. The worktree was already dirty with unrelated prior changes; those changes were preserved.

- Branch: `master`
- HEAD: `d9c99e56e4289c31928dad5a071a552bd75555dc`
- Live application: not accessed; port 8081 untouched.
- No dependencies or configuration changes.

## Affected paths

[RunnerServer.java](../src/main/java/com/example/unifiedqarunner/RunnerServer.java) contains:

- `GET /screenshots/{file}` → `serveScreenshot`.
- `GET /videos/{file}` → `serveVideo`.

Both routes authenticate before dispatch, validate the filename and normalized path, require a regular file, and call `requireArtifactAccess` before opening file content. Before this remediation, both handlers used `Files.readAllBytes`.

Other `readAllBytes` calls were not changed because they serve different purposes, including page/report or process-output handling. No upload, deletion, ZIP, authentication, RBAC, CSRF or tenant-ownership logic was changed.

