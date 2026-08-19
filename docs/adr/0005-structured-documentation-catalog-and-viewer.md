# ADR-0005: Experiment with a structured documentation catalog and viewer

- Status: proposed
- Date: 2026-08-20

## Context

Several project control documents use large Markdown tables. The tables are readable in isolation,
but selecting one record, following its evidence, computing cross-document views, and making a small
automated update all require reparsing surrounding prose. As the semantic inventory and experiment
journal grow, that repeated context makes both human and automated maintenance unnecessarily costly.

Replacing all documentation with structured data would lose the explanations, decisions, and
operational guidance for which prose is the appropriate form. The experiment therefore needs a
narrow ownership boundary and a reversible migration.

## Decision

Add `docs/catalog.ndjson` as the canonical source for the records explicitly listed in its metadata.
Use one self-contained JSON object per line, a schema identifier, globally unique stable IDs, and
typed reference prefixes. The first metadata record defines how internal record links, repository
paths, test IDs, Java test methods, and external URLs are resolved.

Markdown and the HTTP page are projections of catalog facts, not parallel fact owners. Derived
counts, reverse references, filters, and summaries are computed from the records. Narrative portions
of architecture, testing policy, ADRs, task descriptions, environment guides, limitations, and
reports remain Markdown and retain their existing ownership. A migrated document may keep a short
explanation and viewer link, but must not require maintainers to update a duplicate table.

Provide a repository CLI to validate, show, query, and update records and to serve the static viewer.
Validation covers JSON syntax, schema and enum values, global ID uniqueness, reference targets,
repository-relative path safety, and record relationships. The HTTP handler exposes only allowlisted
documentation entry points, the documentation tree, and test sources needed by the viewer; VCS
metadata, build files, and application sources remain unavailable. The static viewer parses the same
NDJSON without a framework and exposes stable record and case deep links. Data is rendered as text;
catalog content is not executable HTML or shell input.

Readiness remains an explicit recorded decision. The viewer and CLI must not infer that an area or
milestone is verified merely because guards exist or a related run passed. Execution evidence remains
owned by dated reports, and readiness status changes continue to follow `docs/TESTING.md`.

## Experimental migration scope

The first migration is deliberately limited to:

- the 27 rename decisions from `docs/SEMANTICS-MATRIX.md`;
- the project snapshot metadata plus baseline, active work, blocker, release risk, evidence request,
  and operating constraint tables from `PROGRESS.md`;
- the area and milestone tables from `docs/READINESS.md`; and
- experiment entries committed at baseline `6b50da9` from `EXPERIMENTS.md`.

Report and archive bodies are not imported. Other semantic axes, test definitions, limitations,
environment descriptions, and general prose are deferred until this experiment establishes that the
format is easier to review and maintain.

## Alternatives considered

- A single JSON array: rejected because an edit rewrites or reparses the whole collection and creates
  broader diffs than a line-oriented catalog.
- YAML: rejected because browser parsing would require another dependency and implicit scalar typing
  makes mechanical updates less predictable.
- SQLite: rejected because binary changes are difficult to review and merge in Git.
- Generated Markdown only: rejected because it does not provide a compact canonical record source or
  stable cross-view identifiers.

## Consequences

- A maintainer can retrieve or change one fact by ID without loading a large Markdown table.
- The viewer can offer filtering, deep links, and reverse references without hand-maintained indexes.
- Catalog schema and link validation become a repository gate and must evolve deliberately.
- NDJSON is less pleasant for unassisted browsing, so the CLI and viewer are part of the experiment,
  not optional conveniences.
- Existing prose remains available and the experiment does not claim that the complete documentation
  model has been migrated.

## Rollback

Keep the experiment isolated on its branch. Commit `6b50da9` in Git history provides the comparison
and rollback baseline. The experiment can be reverted as one change, removing the catalog, CLI,
viewer, and projected entry points. Rollback restores migrated tables from that baseline or renders
them from the last valid catalog before removing the structured tooling. No dated report or archive
body is rewritten during either path.

## References

- [`docs/catalog.ndjson`](../catalog.ndjson)
- [`docs/TESTING.md`](../TESTING.md)
- [`docs/SEMANTICS-MATRIX.md`](../SEMANTICS-MATRIX.md)
- [`PROGRESS.md`](../../PROGRESS.md)
- [`EXPERIMENTS.md`](../../EXPERIMENTS.md)
