# Level 4.1.8C-2F — Risk Selection

## Selected family

**Shared-path configuration and catalog discovery.**

## Verified root cause

`GET /api/shared-paths` and scan/delete routes used `SHARED_PATH` ownership checks, but `RunnerServer.catalogResponse` serialized every configured shared path, including its absolute filesystem path, without applying the same project ownership predicate. A tenant member could therefore receive another tenant's shared-path name/path through `/api/catalog`, even though direct scan/delete operations were denied.

This is a real remaining H-005 confidentiality gap in an existing shared-storage family. It is independently fixable without changing path configuration semantics, migrating storage, or touching remote imports, CI/CD, artifacts, or the execution queue.

## Exact affected boundary

- `GET /api/catalog` → `RunnerServer.catalogResponse` shared-path serialization.
- `GET /api/shared-paths` → already ownership-filtered; retained unchanged.
- `POST /api/shared-paths` → already requires project test-execution access and assigns `SHARED_PATH` ownership; retained unchanged.
- `DELETE /api/shared-paths/{name}` and `POST /api/shared-paths/{name}/scan` → already require owned project access; retained unchanged.
- `SharedPathConfig` → file-backed `qa-runner.json` `sharedPaths[]`; retained unchanged.

## Required change and expected behavior

The catalog now filters each shared path through `ownedProjectAccess(exchange, "SHARED_PATH", name, TEST_EXECUTION, false)` before serialization. Organization A receives only its assigned shared paths; Organization B receives only its own. Unassigned/quarantined entries remain absent for ordinary users. Platform Admin behavior remains the explicit existing platform-admin path.

The family intentionally permits configured absolute directories because that is the feature contract. A broad trusted-root migration would be a storage/feature redesign and is deferred rather than inferred into this batch.

## Deferred families

All other partially remediated families in the reconciliation matrix remain deferred: physical artifact metadata, test-run durability, automation mappings, compatibility stores, CI/CD status cache, plugin result outputs, integration metadata, AI/audit stores, and any future webhook/remote-runner features.

