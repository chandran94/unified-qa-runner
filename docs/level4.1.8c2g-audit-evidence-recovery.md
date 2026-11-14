# Level 4.1.8C-2G — Audit Evidence Recovery

## Result

The original Level 4.1.8A audit was recovered. It was stored under the nested `docs/level4.1.8a/` directory, rather than at the top level of `docs/`. The earlier C-2F report statement that the original audit was unavailable was therefore a search-scope error and is superseded by this report.

## Recovered evidence

- [level4.1.8a-final-report.md](./level4.1.8a/level4.1.8a-final-report.md)
- [level4.1.8a-api-matrix.md](./level4.1.8a/level4.1.8a-api-matrix.md)
- [level4.1.8a-resource-ownership-matrix.md](./level4.1.8a/level4.1.8a-resource-ownership-matrix.md)
- [level4.1.8a-execution-security-audit.md](./level4.1.8a/level4.1.8a-execution-security-audit.md)
- [level4.1.8a-remediation-roadmap.md](./level4.1.8a/level4.1.8a-remediation-roadmap.md)

The recovered final report records 140 route branches, 38 resource families, two Critical findings, five High findings, and an initial H-005 scope covering shared execution, integration, artifact and file-backed stores. Its historical line numbers are not treated as current source locations.

## Bounded recovery searches

The following read-only searches were run from the project root:

```text
rg --files -g '*.md' -g '*.yaml' -g '*.yml' -g '*.json' | rg -i '4\.1\.8|h-005|ownership|remediation|security|audit|roadmap'
rg -n -i 'level4\.1\.8a|h-005|execution-security-audit|resource-ownership-matrix|remediation-roadmap' . -g '!target/**' -g '!*.class' -g '!*.log'
git log --all --name-only --pretty=format: -- docs src | rg -i '4\.1\.8a|h-005|ownership|remediation|security|audit|roadmap'
git log --all --oneline --decorate --grep='4\.1\.8A|H-005|tenant|isolation' -i -n 80
```

The first search located the nested reports. The source/report reference search found historical references in current documentation and source comments. The bounded Git-history name/message searches did not provide a reliable original-report revision identifier in the captured output; no revision is asserted here.

## Evidence treatment

The recovered Level 4.1.8A documents establish the original finding scope and historical claims. Current classifications in [level4.1.8c2g-reconstructed-ownership-matrix.md](./level4.1.8c2g-reconstructed-ownership-matrix.md) require current source evidence and current focused test evidence. Prior remediation reports are treated as reported evidence and are cross-checked against the current implementation; they are not accepted as proof by themselves.

## Limitations

- This phase does not recover or expose credentials, customer data, or live integration responses.
- No live external provider, browser, PostgreSQL, cloud, or production execution was used.
- Historical report line references may differ from current source line references after remediation.
- Existing focused tests cover the previously remediated boundaries, but not every original resource family independently.
