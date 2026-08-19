# Production readiness

Current readiness areas and release milestones are canonical `readiness_area` and `milestone` records
in [`catalog.ndjson`](catalog.ndjson). Only linked dated reports count as verified evidence. The HTTP
viewer projects those records together with their experiment, test-case, limitation, and semantic
references; this file does not duplicate the status matrix.

Status values are `VERIFIED`, `PARTIAL`, `NOT_VERIFIED`, `BLOCKED`, and `ACCEPTED_RISK`.

## View

```bash
python3 scripts/docs-catalog.py serve
python3 scripts/docs-catalog.py query --kind readiness_area
python3 scripts/docs-catalog.py query --kind milestone
```

Active sequencing and environment blockers are `work` records surfaced through
[`../PROGRESS.md`](../PROGRESS.md). Current product restrictions remain in
[`KNOWN-LIMITATIONS.md`](KNOWN-LIMITATIONS.md).

## Update rules

- Change a readiness status only when adding or linking a report that identifies the environment,
  commit, commands, test IDs, and result.
- A partial execution does not verify an entire area.
- A failure remains visible until a later report reruns the same test ID and records the applicable
  resolution or an approved accepted-risk reference.
- Do not infer production readiness from counts or passing runs. Readiness remains an explicit
  decision record.
- Do not copy test steps, environment topology, task history, or report output into readiness
  records.
