# H-005 Mapping Contract Design

`executionId` is an optional execution association, not the mapping identity or ownership key. The supported creation route supplies `testCaseId` and `assetId`, and `AutomationMappingManager.link` persists mappings without inventing an execution ID.

The minimal correction is to authorize mapping lists through the persisted `testCaseId` → project ownership → organization/RBAC chain. Existing `executionId`, when present, is preserved. No schema migration, default-path change, persistence redesign or endpoint addition is required.
