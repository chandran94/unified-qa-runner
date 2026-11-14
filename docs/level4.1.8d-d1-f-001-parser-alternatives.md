# D1-F-001 Parser and Library Alternatives

## Local dependency evidence

[pom.xml](../pom.xml) contains Jakarta Mail, database drivers, Jackson, Selenium, REST Assured, Cucumber, and JUnit dependencies. It does not declare Apache Commons Compress or another dedicated archive parser. No dependency was downloaded or added for this audit.

## Alternatives

| Option | Local availability | Ratio capability | Compatibility/security assessment |
|---|---|---|---|
| Current JDK `ZipInputStream` | Present and used | Actual decompressed bytes only; no exact compressed payload ratio | Lowest change and known current behavior; ratio remains unverified/open. |
| JDK `ZipFile` hybrid | JDK API, not currently used for user imports | Metadata screening plus actual decompressed caps; not exact trusted ratio | No dependency, but requires testing of malformed/ZIP64/data-descriptor variants and does not close the exact-ratio gap. |
| Existing project library adapter | No suitable archive library identified in `pom.xml` | NOT VERIFIED; current dependencies are not archive parsers | No evidence supports selecting one. Do not repurpose unrelated dependencies. |
| Apache Commons Compress or another maintained ZIP library | Not present; would require a new dependency | API details and exact per-entry compressed-payload accounting are NOT VERIFIED locally | Could be viable, but dependency review, version selection, API proof, CVE review, compatibility tests, and approval are required. |
| Hand-written ZIP parser/accounting layer | Not present | Theoretically possible | Highest correctness, parser-security, ZIP64, malformed-input, and maintenance risk; not recommended. |

## Recommended alternative boundary

If exact ratio is a mandatory closure criterion, separately approve a maintained ZIP parser/library only after its local API, version, security advisories, ZIP64/data-descriptor behavior, and per-entry accounting semantics are verified. The adapter must retain current staged-input, actual-byte, path, duplicate, cleanup, and tenant controls.

If exact ratio is not mandatory, explicitly risk-accept the residual ratio limitation while retaining current hard decompressed-byte and entry-count controls. That is a governance decision, not an implicit technical closure.

Confidence: HIGH that no suitable archive dependency is declared locally; LOW/MEDIUM on any external library’s exact API behavior because external sources were not consulted by instruction.
