# Level 4.1.8C-2E — Risk Selection and Remediation Plan

## Selected family

**Remote imports (GitHub and ZIP), including catalog metadata and shared import-directory access.**

This is the highest-risk independently testable existing family because it combines tenant-sensitive source metadata with file-backed test assets. The verified pre-fix issue was an authorization inconsistency: route-level import operations used `REMOTE_IMPORT` ownership, while `catalogResponse` serialized the complete global import list without that filter. Stored paths were also accepted directly by several physical-file paths. This could disclose another tenant's import identity/source/path and could make unsafe legacy path metadata relevant to filesystem access.

## Minimum changes made

1. Filter remote imports in catalog serialization by the existing project ownership and `TEST_EXECUTION` permission.
2. Stop returning local filesystem paths from import list, create, and scan responses.
3. Resolve stored import directories through a real-path check below the trusted `imports` root before scan, catalog discovery, execution discovery, or deletion.
4. Generate ZIP temporary filenames server-side instead of incorporating the client filename into the filesystem path.
5. Sanitize extracted directory names and fail closed for unsafe or missing legacy paths.

No new authorization framework, integration, credential store, webhook protocol, queue, or database was introduced.

## Authorized and denied behavior

- A project member with `TEST_EXECUTION` access sees and operates only imports assigned to that project and organization.
- A member of another organization/project cannot list, scan, delete, or discover the selected import.
- Missing ownership remains quarantined and is not assigned to the current tenant.
- Unauthenticated API access remains rejected by the existing API authentication gate; protected mutations retain same-origin and CSRF checks.
- Platform Admin handling remains separate and unchanged.

## Deferred families

CI/CD execution/result integrations, database connections, shared paths, reports outside C-2C, screenshots/videos, automation execution records, and all nonexistent/documentation-only provider integrations are deferred. Their prior controls were not weakened.

## Required focused verification

- Organization A/B catalog and `/api/imports` filtering.
- No local path in serialized import metadata.
- Trusted-root rejection for an outside-root legacy path.
- Existing tenant, project-RBAC, async context, C-2A, C-2B, C-2C, and Level 3 focused regressions.

