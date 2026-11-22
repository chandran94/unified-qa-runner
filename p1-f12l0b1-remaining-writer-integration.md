# P1-F12L.0B.1 — Remaining Writer Integration

## Coverage matrix

| Writer | Implementation | Status |
|---|---|---|
| TestCaseManager | Shared `qa-runner.json` write boundaries | Integrated and compiled |
| CiCdConfig | Global and project-scoped JSON saves | Integrated and compiled |
| DatabaseConnectionRepository | Atomic metadata write | Integrated and compiled |
| ProjectOwnershipManager | Ownership index write | Integrated and compiled |
| AutomationMappingManager | Mapping write | Integrated and compiled |
| AutomationExecutionManager | Durable execution history write | Integrated and compiled |
| SharedPathConfig | Preserving/coordinated JSON write | Integrated; data-loss defect fixed |
| RemoteImportConfig | Preserving/coordinated JSON write | Integrated; data-loss defect fixed |
| Attachments | RunnerServer direct file publication/deletion | Not yet coordinated |
| Screenshots/videos/reports | Plugin and runner artifact writers | Not yet coordinated |

All coordinated JSON writers share the data-root coordinator. Existing atomic replacement behavior was retained where present. No external database query or network operation was placed under the storage lock.

## Confirmed defect fixed

`SharedPathConfig.save()` and `RemoteImportConfig.save()` previously reconstructed `qa-runner.json` from only their own sections, risking loss of unrelated persisted module data. Both now load and preserve the complete existing JSON root before updating their section and coordinate the final write.

## Remaining blocker

Direct attachment publication/deletion and durable `target/` artifact writers are not yet coordinated. The coordinator therefore continues to fail closed for a fully consistent backup. No complete backup was run against live or test application data in this phase.

## P1-F12L.0B.2 delta

Attachment publication/deletion and `ReportWriter` summary/index publication now use the shared per-root coordinator. Screenshot/video deletion is coordinated, but their external producers are not. Several plugin-specific direct report/log writes also remain outside the common publication seam. Shared-path/import JSON saves now merge inside the barrier and have concurrent preservation regression coverage. Backup consistency remains **BLOCKED**.
