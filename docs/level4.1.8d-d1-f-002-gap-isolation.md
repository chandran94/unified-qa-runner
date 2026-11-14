# D1-F-002 Gap Isolation Evidence

| Requirement | Evidence | Result |
|---|---|---|
| Synthetic identities/projects | Fixture creates disposable users, organizations, and projects | PASS |
| Disposable persistence | `Files.createTempDirectory("qa-media-stream-")`; local `qa-runner.json` only | PASS |
| Disposable media | Media files are created below the temporary `target/screenshots` and `target/videos` roots | PASS |
| Loopback server | `RunnerServer(root, 0, null, null)` uses OS-assigned port | PASS |
| No live port 8081 | Tests use the returned ephemeral URI | PASS |
| No external integrations | No external HTTP or service dependency in the focused fixture | PASS |
| Bounded data | Fixtures are small; existing media is about 48 KiB each | PASS |
| Cleanup | `AfterEach` closes server and walks only the task-owned temporary root | PASS |
| Symlink capability | `Files.createSymbolicLink` was attempted without elevation | SKIPPED/BLOCKED on this Windows environment |

The symlink test deliberately uses a capability gate. It does not convert unavailable Windows symlink support into a security pass. No production or user data path is used.

