# Project progress

Current baseline, active work, blockers, release risks, requested evidence, and operating constraints
are canonical `project`, `baseline`, `work`, `risk`, `evidence_request`, and `constraint` records in
[`docs/catalog.ndjson`](docs/catalog.ndjson). This file is only the repository entry point; it does
not repeat the structured state.

## View

Start the local viewer from the repository root:

```bash
python3 scripts/docs-catalog.py serve
```

Then open the URL printed by the command. The Project view derives its tables and cross-links from
the catalog. The same records remain available without a browser:

```bash
python3 scripts/docs-catalog.py show PROJECT-STATUS
python3 scripts/docs-catalog.py query --kind baseline
python3 scripts/docs-catalog.py query --kind work
python3 scripts/docs-catalog.py query --kind risk
python3 scripts/docs-catalog.py show T08
```

## Update rules

- Maintain active and blocked work as `work` records; remove resolved work in the same change that
  indexes its resolution in `docs/archive/YYYY-MM.md`.
- Store links as stable IDs or repository-relative paths. Do not duplicate report observations,
  task history, or limitation explanations in project-state records.
- Validate every edit with `python3 scripts/docs-catalog.py validate` and the repository documentation
  gate.
