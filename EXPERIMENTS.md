# Experiment journal

The operational journal is canonical as `run` records in
[`docs/catalog.ndjson`](docs/catalog.ndjson). Each record carries a stable ID, date, result, concise
details, and links to any retained evidence. This file is only a compatibility entry point and does
not duplicate individual runs.

Query a single run or a filtered set without loading the whole history:

```bash
python3 scripts/docs-catalog.py query --kind run
python3 scripts/docs-catalog.py show RUN-20260820-SEMANTIC-SCHEMA-GATE
```

Manual maintainers can preview and atomically append a prepared run object with
`python3 scripts/docs-catalog.py add-run ./run-record.json`, then repeat the command with `--write`.
Repository agents must follow the edit and validation rules in [`AGENTS.md`](AGENTS.md).

Dated release-gate evidence remains in [`docs/reports/`](docs/reports/) under the policy in
[`docs/TESTING.md`](docs/TESTING.md).
