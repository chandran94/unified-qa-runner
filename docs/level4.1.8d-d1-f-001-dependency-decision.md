# D1-F-001 Dependency Decision

## Decision

**No dependency added. Phase A failed.**

The project `pom.xml` contains no ZIP parser dependency. The local Maven cache contains Apache Commons Compress artifacts, but that does not establish that the project may use them, that a specific version is approved, or that the required compressed-payload accounting API is available and correct.

## Candidate assessment

| Candidate | Local evidence | Gate status | Decision |
|---|---|---|---|
| JDK `ZipInputStream` | Used by current import path | No exact compressed-payload accounting | Retain existing hard limits; not sufficient for ratio closure |
| JDK `ZipFile` | JDK API conceptually available | Size metadata only; exact semantics and edge cases not verified here | Not selected as exact-ratio solution |
| Apache Commons Compress 1.28.0 | Local POM only; no local JAR/source | API cannot be inspected under current evidence constraints | Not approved |
| Apache Commons Compress 1.26.2 | Local JAR; no source/API evidence inspected | Counter semantics not proven | Not approved |
| Apache Commons Compress 1.21 | Local JAR and source artifact exist | Not project-declared; source not extracted or executed | Not approved |
| Hand-written parser/counter | No local implementation | High parser and compatibility risk | Prohibited/not recommended |

## Smallest next action

Obtain separate approval for a bounded dependency/API verification step that can inspect the selected library’s official API/source and dependency/security metadata without arbitrary artifact fetching. The verification must prove:

- Exact class and method signatures.
- Actual versus metadata-based compressed-byte semantics.
- Per-entry and incremental availability.
- ZIP64 and data-descriptor behavior.
- Supported compression methods and safe rejection of unsupported/unknown cases.
- Streaming, bounded-memory behavior and deterministic closure.

Until that evidence exists, adding a dependency or changing ZIP extraction would be speculative and outside the gate.
