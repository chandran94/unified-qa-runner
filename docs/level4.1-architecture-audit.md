# Level 4.1.1 Architecture Audit and Multi-Tenancy Gap Matrix

Date: 2026-11-13  
Scope: Unified QA Runner before Level 4.1 organization implementation  
Change status: Audit only; no application source or running instance was modified.

## Executive assessment

The application is a single-process Java HTTP server built on `com.sun.net.httpserver.HttpServer`. `RunnerServer` is the central request router and authorization enforcement point. Authentication is persistent JSON user data plus in-memory opaque sessions and CSRF tokens. Domain data is primarily stored in a shared `qa-runner.json` compatibility document, with atomic temporary-file replacement in several managers.

The current security boundary is project-scoped, not organization-scoped. `ProjectOwnershipManager` is authoritative for selected resources by mapping `resourceType/resourceId` to one project, while `TeamMemberManager` and `TeamAuthorization` resolve project roles from user membership records. There is no organization identifier on projects, users, memberships, or resource ownership records. Consequently, organization isolation cannot be safely added by frontend filtering or by treating the existing project role as an organization role.

The Level 3 evidence is useful and remains a baseline: the isolated four-role browser test passed, cross-project requests returned expected 403/401 responses, CSRF and logout were verified, and OTPs remained in the in-memory test adapter. It does not provide organization-tenant evidence because no organization model or tenant context exists yet.

## Current architecture

### Runtime and request flow

1. `Main` locates a project root and starts one `RunnerServer` instance.
2. `RunnerServer` routes static files, authentication APIs, domain APIs, file downloads, execution, reports, imports and CI/CD operations.
3. Authentication uses `AuthUserDirectory`, `AuthVerificationManager`, `AuthSessionManager`, and `AuthRegistrationRateLimiter`.
4. Authorization uses `TeamAuthorization`, `TeamMemberManager`, `TeamRoleManager`, project IDs, and `ProjectOwnershipManager`.
5. Background execution uses `ExecutionService` and a single run executor; the current run model carries project ownership through the test-run record and artifact registration, but has no tenant context.
6. Most persistence is JSON document mutation with preservation of unknown root fields and atomic replacement. There is no application-level verified-backup coordinator or rollback journal.

### Identity and RBAC

- `AuthUserDirectory.User` is a single global identity record with username/email/mobile, password hash, verification flags, status, and timestamps.
- `AuthSessionManager` stores sessions in memory, keyed by opaque cookie IDs; one active session is retained per user when a new session is created.
- `TeamMemberManager.Member` represents project/team membership and contains a role plus `projectIds` and optional project role mappings.
- `TeamRoleManager` defines built-in roles including `Admin`, `Project Manager`, `QA Lead`, `QA Engineer`, `Developer`, `Product Owner`, and `Viewer`.
- `TeamAuthorization` makes `Admin` a global bypass for project ownership checks. There are no distinct Organization Owner, Organization Admin, Organization Member, or Organization Viewer roles.
- The development verification adapter is only selected when `QA_RUNNER_AUTH_DEV_VERIFICATION=true`, `QA_RUNNER_ENV=development`, and `QA_RUNNER_LOCAL_ONLY=true`; normal startup does not inject it.

### Persistence and compatibility

- The compatibility boundary is generally `qa-runner.json`.
- Versioning is fragmented: `UserStoryStore` has schema version 5, `ProjectOwnershipManager` has ownership schema version 1, and Wiki has a separate schema version. Authentication, team, CI/CD, imports, shared paths, automation and database records use independent root keys without one organization-aware envelope version.
- Several managers use atomic temporary-file replacement. No code path was found that creates a verified pre-mutation backup, records a migration checkpoint, or exposes rollback for a tenant migration.
- Existing projects, stories, test cases, defects, wiki records, user accounts and related records must therefore be migrated additively and quarantined when ownership cannot be proven.

## Resource and endpoint inventory

| Resource family | Current authoritative relationship | Current persistence/handler | Organization readiness |
|---|---|---|---|
| Users/authentication | Global user ID; no tenant membership | `AuthUserDirectory`, auth routes | Identity can be retained; organization memberships absent |
| Team management/roles | User + role + project IDs | `TeamMemberManager`, `TeamRoleManager` | Project RBAC only; Admin is global |
| Projects | Project ID, key, name, owner string | `UserStoryStore` | No immutable `organizationId` |
| User stories, epics, sprints | `projectId` | `UserStoryStore` and RunnerServer | Tenant derivable only after project org assignment |
| Test cases/suites | Test-case records plus P0.12 ownership records | `TestCaseManager`, `ProjectOwnershipManager` | Project ownership exists for selected records; no tenant layer |
| Defects | Optional project ID in defect map | `DefectManager` | Needs authoritative project/tenant relationship |
| Wiki spaces/pages | P0.11 ownership records for selected resources | `WikiManager`, RunnerServer | Project layer exists; tenant layer absent |
| Runs/execution history | Run ID and project ownership association | `RunnerServer`, `ExecutionService`, `AutomationExecutionManager` | Worker propagation is project-aware but not tenant-aware |
| Reports | Run report directory under project target | RunnerServer report route | Needs run-to-project-to-tenant authorization and quarantine |
| Screenshots/videos | Artifact ownership keyed by filename and run | RunnerServer, `ProjectOwnershipManager` | Path checks and project checks exist; filenames are not tenant keys |
| User-story attachments | Story ID and filesystem storage name | `UserStoryStore`, RunnerServer | Must resolve through story project and tenant before download |
| Automation assets/mappings | Mixed global/configured records | `AutomationMappingManager`, RunnerServer | Some legacy endpoints remain admin-only when unowned |
| Local runner assets | Files resolved under project root/configured paths | RunnerServer/ExecutionService/plugins | Needs tenant context and canonical-root enforcement |
| Shared paths | Configured path entries with project scoping | `SharedPathConfig`, RunnerServer | Project ownership added; tenant relationship absent |
| Remote imports | Import records and imported files | `RemoteImportConfig`, RunnerServer | Project ownership added; tenant relationship absent |
| CI/CD configuration | One project-scoped config with `projectId` | `CiCdConfig`, RunnerServer | Project scope exists; organization scope absent |
| CI/CD execution history | Execution/run records | `PipelineStatusService`, automation/run stores | Needs explicit project and tenant ownership |
| Database connections | Config records under compatibility store | `DatabaseConnectionRepository/Service` | Sensitive data is protected, but tenant context absent |

## Multi-tenancy gap matrix

| Requirement | Existing evidence | Gap | Required Level 4.1 control | Risk |
|---|---|---|---|---|
| User belongs to multiple organizations | One global user record; project membership only | No membership entity or active-org context | Add versioned Organization, Membership and Invitation records; retain one identity credential set | Critical |
| Project belongs to exactly one organization | Project has no organization field | Cross-tenant relation cannot be proven | Immutable authoritative `organizationId` on project; reject reassignment | Critical |
| Tenant context per request | Authorization receives session and project IDs only | No request-scoped tenant object | Add immutable `TenantContextResolver`; authenticate, resolve org, validate membership, then project/resource | Critical |
| Organization RBAC | Global Admin and project roles | Organization Admin could be confused with Platform Admin | Separate platform and organization policies; protect last owner | Critical |
| Organization APIs | No `/api/organizations` routes | No creation, switching, membership or invitations | Add authenticated CSRF-protected routes with pagination and rate limits | Critical |
| Resource tenant ownership | Project ownership index only | No org link for any resource | Resolve resource → project → organization server-side; deny mismatches | Critical |
| Search/count/pagination isolation | Some project filters in stores and handlers | No universal tenant predicate | Centralize scoped query/list helpers and test counts/search/export | High |
| Background execution | Single run executor; project ownership captured | Tenant context is not explicitly propagated | Validate and carry immutable tenant context into worker jobs | High |
| File/path isolation | Several canonical path checks and attachment limits | No tenant root and incomplete audit across assets/imports/reports | Canonicalize roots, reject traversal/symlink escapes, authorize before open | Critical |
| Invitation security | No invitation entity or flow | No token hash, expiry, single-use or identity binding | Add hashed random tokens, expiry, acceptance state, abuse limits | High |
| Legacy migration | Additive project ownership quarantine; atomic writes | No verified backup/review/rollback service; no org evidence | Backup manifest, idempotent migration, unresolved admin-only review | Critical |
| JSON versioning | Independent schema keys | No global migration version | Add versioned tenant envelope while preserving unknown keys | High |
| Organization-aware frontend | Single project-oriented UI | No organization switcher or cache invalidation | Add authorized-org switcher and context-aware management UI | Medium |
| Production database compatibility | JSON only; PostgreSQL deferred | Models need stable IDs/constraints and no JSON-only assumptions | Design records and repositories with future relational constraints in mind | Medium |

## Endpoint authorization audit

The central router currently exposes authentication routes, organization-absent project/domain routes, file routes, execution routes, imports, shared paths, CI/CD and admin ownership routes. Each protected endpoint will need one of these explicit policies in 4.1:

- platform-global policy: platform admin only;
- organization policy: active organization membership plus organization role;
- project policy: resolved project belongs to active organization, then project role permission;
- resource policy: resource resolves to project and organization before read/write/download/execute;
- legacy policy: unresolved ownership is admin-only and cannot be silently inferred.

The current `isPublicAuthApi` and route-local authorization structure should not be expanded as an organization shortcut. Organization context must be established before route-specific permission checks, and any request-scoped context must be cleared after handling pooled HTTP threads.

## Legacy migration assessment

No mutation was performed during this audit. Existing data remains untouched. The safe migration design must:

1. Create and verify a backup of the source JSON and file roots before any schema mutation.
2. Derive organization ownership only from trustworthy project evidence.
3. Quarantine projects/resources with ambiguous ownership and expose them only to authorized administrators.
4. Preserve unknown JSON root fields and use atomic replacement with recovery metadata.
5. Be idempotent and produce resolved/unresolved counts by resource type.
6. Never assign all records to the first user or silently change project ownership.

## Recommended implementation boundary

Implement 4.1 incrementally inside the current JSON boundary:

1. Add organization domain records, validation, schema envelope/version and repository-style persistence helpers.
2. Add tenant context and organization/project authorization primitives without rewriting existing domain managers.
3. Assign existing projects only when evidence is trustworthy; quarantine the rest.
4. Migrate endpoint families one at a time through shared authorization helpers.
5. Add organization APIs and UI after server-side context is proven.
6. Add multi-tenant browser fixtures and resource-by-resource regression coverage.
7. Package to a separate artifact and validate without touching the running application.

Level 4.2 PostgreSQL work is explicitly out of scope for this audit and implementation stage.

## Audit conclusion

The existing Level 3 project isolation is a usable security foundation, but it is not an organization-aware SaaS boundary. The first implementation gate must be the organization model plus tenant context resolver; adding UI or broad endpoint changes before those controls would create a security regression risk. No Level 4.1 implementation has started after this audit.
