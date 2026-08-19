#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

failures=0

fail() {
  printf '[check-docs] FAIL: %s\n' "$*" >&2
  failures=$((failures + 1))
}

note() {
  printf '[check-docs] %s\n' "$*"
}

for tool in rg python3 wc; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    printf '[check-docs] ERROR: required tool is missing: %s\n' "$tool" >&2
    exit 2
  fi
done

progress_lines="$(wc -l < PROGRESS.md)"
progress_bytes="$(wc -c < PROGRESS.md)"
if (( progress_lines > 120 )); then
  fail "PROGRESS.md has $progress_lines lines; maximum is 120"
fi
if (( progress_bytes > 8192 )); then
  fail "PROGRESS.md has $progress_bytes bytes; maximum is 8192"
fi

if rg -n '^(##+).*(DONE|RESOLVED|Completed tasks|Resolved|已完成|已解决)' PROGRESS.md; then
  fail 'PROGRESS.md contains a completed or resolved history heading'
fi

long_lived=(
  AGENTS.md
  README.md
  PROGRESS.md
  docs/README.md
  docs/ARCHITECTURE.md
  docs/DEVELOP.md
  docs/DEPLOY.md
  docs/TESTING.md
  docs/READINESS.md
  docs/KNOWN-LIMITATIONS.md
)

if rg -n '/home/[^[:space:]`]+|/Users/[^[:space:]`]+|C:\\Users\\|application_[0-9_]+' "${long_lived[@]}"; then
  fail 'long-lived documentation contains a local user path or temporary application ID'
fi

if ! python3 <<'PY'
from __future__ import annotations

import pathlib
import re
import sys
import urllib.parse

root = pathlib.Path('.').resolve()
link_re = re.compile(r'!?(?<!\!)\[[^\]]*\]\(([^)]+)\)')
errors: list[str] = []

for doc in sorted(root.rglob('*.md')):
    if '.git' in doc.parts:
        continue
    for lineno, line in enumerate(doc.read_text(encoding='utf-8').splitlines(), 1):
        for match in link_re.finditer(line):
            raw = match.group(1).strip()
            if raw.startswith('<') and '>' in raw:
                raw = raw[1:raw.index('>')]
            else:
                raw = raw.split(maxsplit=1)[0]
            if not raw or raw.startswith(('#', 'http://', 'https://', 'mailto:')):
                continue
            path_text = urllib.parse.unquote(raw.split('#', 1)[0].split('?', 1)[0])
            if not path_text:
                continue
            target = (doc.parent / path_text).resolve()
            try:
                target.relative_to(root)
            except ValueError:
                errors.append(f'{doc.relative_to(root)}:{lineno}: link leaves repository: {raw}')
                continue
            if not target.exists():
                errors.append(f'{doc.relative_to(root)}:{lineno}: missing target: {raw}')

if errors:
    print('\n'.join(errors), file=sys.stderr)
    raise SystemExit(1)
PY
then
  fail 'one or more relative Markdown links are invalid'
fi

if ! python3 <<'PY'
from __future__ import annotations

import pathlib
import re
import sys

files = [pathlib.Path('docs/TEST-PLAN.md'), pathlib.Path('docs/TEST-CASES-ECO.md')]
heading = re.compile(r'^## ([A-Z][A-Z0-9]*(?:-[A-Z0-9]+)+):\s+(.+)$')
priority = re.compile(r'\[P[0-2](?: [a-z]+)?\]$')
required = ('Expected result', 'Required environment')
definitions: dict[str, tuple[pathlib.Path, int]] = {}
errors: list[str] = []

for path in files:
    lines = path.read_text(encoding='utf-8').splitlines()
    positions: list[tuple[int, str]] = []
    for index, line in enumerate(lines):
        match = heading.match(line)
        if match:
            positions.append((index, match.group(1)))
            # The heading is the only place a case states its priority.
            if not priority.search(match.group(2).strip()):
                errors.append(
                    f'{path}:{index + 1}: {match.group(1)} heading has no [P0]/[P1]/[P2] tag'
                )
    for position, (index, test_id) in enumerate(positions):
        if test_id in definitions:
            previous = definitions[test_id]
            errors.append(
                f'{path}:{index + 1}: duplicate {test_id}; first defined at '
                f'{previous[0]}:{previous[1]}'
            )
        else:
            definitions[test_id] = (path, index + 1)
        end = positions[position + 1][0] if position + 1 < len(positions) else len(lines)
        body = lines[index + 1:end]
        present = {
            match.group(1)
            for line in body
            if (match := re.match(r'^- ([A-Za-z ]+):\s*\S', line))
        }
        missing = [field for field in required if field not in present]
        if missing:
            errors.append(f'{path}:{index + 1}: {test_id} missing fields: {", ".join(missing)}')

if not definitions:
    errors.append('no test definitions found')
if errors:
    print('\n'.join(errors), file=sys.stderr)
    raise SystemExit(1)
print(f'[check-docs] test definitions: {len(definitions)} unique IDs')
PY
then
  fail 'test case definitions are incomplete or duplicated'
fi

if ! python3 <<'PY'
from __future__ import annotations

import pathlib
import re
import sys

root = pathlib.Path('.')
excluded_parts = {'.git', 'archive', 'reports'}
paragraphs: dict[str, set[str]] = {}

for path in sorted(root.rglob('*.md')):
    if any(part in excluded_parts for part in path.parts):
        continue
    in_fence = False
    current: list[str] = []

    def flush() -> None:
        if not current:
            return
        normalized = re.sub(r'\s+', ' ', ' '.join(current)).strip()
        current.clear()
        if len(normalized) > 120:
            paragraphs.setdefault(normalized, set()).add(str(path))

    for line in path.read_text(encoding='utf-8').splitlines():
        if line.lstrip().startswith(('```', '~~~')):
            flush()
            in_fence = not in_fence
            continue
        if in_fence or line.startswith('|'):
            flush()
            continue
        if not line.strip():
            flush()
            continue
        current.append(line.strip())
    flush()

duplicates = [(text, paths) for text, paths in paragraphs.items() if len(paths) > 1]
if duplicates:
    for text, paths in duplicates:
        print(f'duplicate paragraph in {", ".join(sorted(paths))}: {text[:180]}', file=sys.stderr)
    raise SystemExit(1)
PY
then
  fail 'active documents contain duplicate normalized paragraphs longer than 120 characters'
fi

if ! python3 <<'PY'
from __future__ import annotations

import datetime as dt
import pathlib
import re
import sys

allowed_types = {'task', 'bug', 'decision', 'environment', 'limitation'}
errors: list[str] = []
for path in sorted(pathlib.Path('docs/archive').glob('*.md')):
    if not re.fullmatch(r'\d{4}-\d{2}\.md', path.name):
        errors.append(f'{path}: invalid archive filename')
        continue
    rows = [line for line in path.read_text(encoding='utf-8').splitlines() if line.startswith('|')]
    if not rows or [cell.strip() for cell in rows[0].strip('|').split('|')] != [
        'Date', 'ID', 'Type', 'Problem', 'Resolution', 'Verification', 'Ref'
    ]:
        errors.append(f'{path}: missing seven-field archive header')
        continue
    for lineno, row in enumerate(rows[2:], 4):
        cells = [cell.strip() for cell in row.strip('|').split('|')]
        if len(cells) != 7:
            errors.append(f'{path}:{lineno}: expected seven fields, found {len(cells)}')
            continue
        try:
            date = dt.date.fromisoformat(cells[0])
        except ValueError:
            errors.append(f'{path}:{lineno}: invalid date: {cells[0]}')
            continue
        if date.strftime('%Y-%m') != path.stem:
            errors.append(f'{path}:{lineno}: date does not match archive month')
        if cells[2] not in allowed_types:
            errors.append(f'{path}:{lineno}: invalid type: {cells[2]}')
        if any(not cell for cell in cells):
            errors.append(f'{path}:{lineno}: empty archive field')

if errors:
    print('\n'.join(errors), file=sys.stderr)
    raise SystemExit(1)
PY
then
  fail 'archive format validation failed'
fi

if ! python3 <<'PY'
from __future__ import annotations

from collections import Counter
import pathlib
import re
import sys

matrix = pathlib.Path('docs/SEMANTICS-MATRIX.md')
case_files = [pathlib.Path('docs/TEST-PLAN.md'), pathlib.Path('docs/TEST-CASES-ECO.md')]

heading = re.compile(r'^## ([A-Z][A-Z0-9]*(?:-[A-Z0-9]+)+):\s')
delegation = (
    '- Expected result: Defined by [SEMANTICS-MATRIX.md](SEMANTICS-MATRIX.md) '
    'rows citing this ID.'
)

defined: set[str] = set()
# A case delegates when its only expected result is the pointer above; the matrix then owns it.
delegating: dict[str, tuple[pathlib.Path, int]] = {}
for path in case_files:
    current: str | None = None
    current_line = 0
    for lineno, line in enumerate(path.read_text(encoding='utf-8').splitlines(), 1):
        match = heading.match(line)
        if match:
            current = match.group(1)
            current_line = lineno
            defined.add(current)
        elif line.strip() == delegation and current is not None:
            delegating[current] = (path, current_line)

errors: list[str] = []
cited_id = re.compile(
    r'\b((?:UT|CT|FN|REL-F|PERF|SOAK|COMPAT|SEC|OPS|NEG|SP|ECO-[A-Z]+)-[0-9]+)\b'
)
allowed_coverage = {'UNIT', 'CONTRACT', 'CLUSTER', 'SPIKE', 'NONE'}
allowed_classification = {'MATCH', 'DIFFERENT', 'UNSUPPORTED', 'UNKNOWN'}
# Legacy current-behavior vocabulary retained until each axis moves to the eight-column schema.
MATCHES = '\u7b26\u5408'
SOURCE_JUDGMENT = '\u6e90\u7801\u5224\u65ad'
UNKNOWN = '\u672a\u77e5'
EMPTY_CASE = '\u2014'
owned: set[str] = set()
seen_coverage = False
semantic_axes = {
    'rename', 'create', 'delete', 'sync', 'visibility', 'identity', 'append',
    'mkdirs', 'path-entrypoint', 'metadata', 'read', 'status-defaults',
    'block-location',
}
computed: dict[str, Counter[str]] = {axis: Counter() for axis in semantic_axes}
current_axis: str | None = None

matrix_lines = matrix.read_text(encoding='utf-8').splitlines()
for index, line in enumerate(matrix_lines):
    lineno = index + 1
    if line.startswith('## '):
        section = line.removeprefix('## ').strip()
        current_axis = section if section in semantic_axes else None
        continue
    if not line.startswith('|'):
        continue
    cells = [cell.strip() for cell in line.strip('|').split('|')]
    for test_id in cited_id.findall(line):
        if test_id not in defined:
            errors.append(f'{matrix}:{lineno}: cites undefined case ID: {test_id}')
    # Semantics rows use either the legacy five-column shape or the migrated eight-column shape.
    # A header row is directly above a separator, keeping this independent of translated headings.
    following = matrix_lines[index + 1] if index + 1 < len(matrix_lines) else ''
    if len(cells) not in {5, 8} or cells[0].startswith('---') or following.startswith('|---'):
        continue

    migrated = len(cells) == 8
    behavior_index = 3 if migrated else 2
    guard_index = 5 if migrated else 3
    coverage_index = 6 if migrated else 4
    guard = cells[guard_index]
    owned.update(cited_id.findall(guard))

    coverage = {token for token in re.findall(r'[A-Z_]{3,}', cells[coverage_index])}
    if not coverage:
        errors.append(f'{matrix}:{lineno}: row has no Coverage')
        continue
    seen_coverage = True
    unknown_coverage = coverage - allowed_coverage
    if unknown_coverage:
        errors.append(
            f'{matrix}:{lineno}: unknown Coverage: {", ".join(sorted(unknown_coverage))}'
        )
    if 'NONE' in coverage and len(coverage) != 1:
        errors.append(f'{matrix}:{lineno}: NONE cannot be combined with another Coverage')

    if current_axis is not None:
        computed[current_axis]['rows'] += 1
        computed[current_axis].update(coverage & allowed_coverage)

    behavior = cells[behavior_index]
    asserted = bool(coverage & {'UNIT', 'CONTRACT', 'CLUSTER'})
    if not migrated:
        if behavior.startswith(MATCHES) and SOURCE_JUDGMENT not in behavior and not asserted:
            errors.append(
                f'{matrix}:{lineno}: behavior claims an enforced match without '
                f'UNIT, CONTRACT, or CLUSTER'
            )
        if (SOURCE_JUDGMENT in behavior or behavior.startswith(UNKNOWN)) and asserted:
            errors.append(
                f'{matrix}:{lineno}: behavior claims source-only or unknown behavior while '
                f'Coverage names an automated assertion'
            )
        continue

    basis = [part.strip().strip('`') for part in cells[2].split('+')]
    invalid_basis = [
        value for value in basis
        if value not in {'HADOOP-SPEC', 'HDFS-3.3.6'}
        and re.fullmatch(r'PROJECT-ADR-[0-9]{4}', value) is None
    ]
    if not basis or any(not value for value in basis) or invalid_basis:
        errors.append(
            f'{matrix}:{lineno}: invalid Basis: {", ".join(invalid_basis or basis)}'
        )

    classification = cells[4].strip('`')
    if classification not in allowed_classification:
        errors.append(f'{matrix}:{lineno}: invalid Classification: {classification}')
    elif current_axis is not None:
        computed[current_axis]['migrated_rows'] += 1
        computed[current_axis][classification] += 1

    tracking = cells[7]
    if classification == 'DIFFERENT' and not re.search(r'\b(?:LIM|ADR)-[0-9]{3,4}\b', tracking):
        errors.append(
            f'{matrix}:{lineno}: DIFFERENT row must cite a limitation or ADR'
        )
    if classification == 'UNKNOWN' and coverage != {'NONE'}:
        errors.append(f'{matrix}:{lineno}: UNKNOWN row must use Coverage NONE')

    project_basis = [value.removeprefix('PROJECT-') for value in basis
                     if value.startswith('PROJECT-')]
    for decision in project_basis:
        if decision not in tracking:
            errors.append(
                f'{matrix}:{lineno}: project Basis {decision} is not linked in Limitation / ADR'
            )

    automated = coverage & {'UNIT', 'CONTRACT', 'CLUSTER'}
    if automated and '#' not in guard:
        errors.append(f'{matrix}:{lineno}: automated Coverage requires an exact Class#method Guard')
    if 'UNIT' in coverage and re.search(r'`Test[A-Za-z0-9]+#[A-Za-z0-9]+`', guard) is None:
        errors.append(f'{matrix}:{lineno}: UNIT Coverage requires a Test*#method Guard')
    if 'CONTRACT' in coverage and 'ITestCephContract' not in guard:
        errors.append(f'{matrix}:{lineno}: CONTRACT Coverage requires an ITestCephContract* Guard')
    if 'CLUSTER' in coverage and re.search(r'`ITest[A-Za-z0-9]+#[A-Za-z0-9]+`', guard) is None:
        errors.append(f'{matrix}:{lineno}: CLUSTER Coverage requires an ITest*#method Guard')
    if 'SPIKE' in coverage and re.search(r'\b(?:SP|ECO-[A-Z]+)-[0-9]+\b', guard) is None:
        errors.append(f'{matrix}:{lineno}: SPIKE Coverage requires a stable spike Guard')
    if coverage != {'NONE'} and guard == EMPTY_CASE:
        errors.append(f'{matrix}:{lineno}: non-NONE Coverage requires a Guard')


def parse_overview(title: str) -> list[list[str]]:
    """Return Markdown table rows between an exact H2 and the next H2."""
    try:
        start = matrix_lines.index(f'## {title}') + 1
    except ValueError:
        errors.append(f'{matrix}: missing overview: {title}')
        return []
    rows: list[list[str]] = []
    for line in matrix_lines[start:]:
        if line.startswith('## '):
            break
        if line.startswith('|'):
            cells = [cell.strip() for cell in line.strip('|').split('|')]
            if not cells[0].startswith('---'):
                rows.append(cells)
    return rows


coverage_rows = parse_overview('Coverage \u6982\u89c8')
coverage_header = ['\u8f74', '\u884c\u6570', 'UNIT', 'CONTRACT', 'CLUSTER', 'SPIKE', 'NONE']
if not coverage_rows or coverage_rows[0] != coverage_header:
    errors.append(f'{matrix}: Coverage overview has an unexpected header')
else:
    declared_coverage: dict[str, list[int]] = {}
    declared_total: list[int] | None = None
    for cells in coverage_rows[1:]:
        name = cells[0]
        if len(cells) != len(coverage_header):
            errors.append(f'{matrix}: malformed Coverage overview row: {name}')
            continue
        try:
            values = [int(cell.strip('*')) for cell in cells[1:]]
        except ValueError:
            errors.append(f'{matrix}: non-numeric Coverage overview row: {name}')
            continue
        if name == '**\u5408\u8ba1**':
            declared_total = values
        elif name in semantic_axes:
            declared_coverage[name] = values

    for axis in sorted(semantic_axes):
        counter = computed[axis]
        actual = [counter['rows']] + [counter[name] for name in coverage_header[2:]]
        if declared_coverage.get(axis) != actual:
            errors.append(
                f'{matrix}: Coverage overview mismatch for {axis}: '
                f'declared={declared_coverage.get(axis)}, actual={actual}'
            )
    actual_total = [
        sum(computed[axis][key] for axis in semantic_axes)
        for key in ['rows'] + coverage_header[2:]
    ]
    if declared_total != actual_total:
        errors.append(
            f'{matrix}: Coverage overview total mismatch: '
            f'declared={declared_total}, actual={actual_total}'
        )

classification_rows = parse_overview('Classification \u6982\u89c8')
classification_header = [
    '\u8303\u56f4', '\u884c\u6570', 'MATCH', 'DIFFERENT', 'UNSUPPORTED', 'UNKNOWN'
]
if not classification_rows or classification_rows[0] != classification_header:
    errors.append(f'{matrix}: Classification overview has an unexpected header')
else:
    declared_classification: dict[str, list[int]] = {}
    for cells in classification_rows[1:]:
        axis = cells[0].split('\uff08', 1)[0]
        if axis not in semantic_axes or len(cells) != len(classification_header):
            continue
        try:
            declared_classification[axis] = [int(cell.strip('*')) for cell in cells[1:]]
        except ValueError:
            continue
    for axis in sorted(semantic_axes):
        counter = computed[axis]
        if not counter['migrated_rows']:
            continue
        actual = [counter['migrated_rows']] + [
            counter[name] for name in classification_header[2:]
        ]
        if declared_classification.get(axis) != actual:
            errors.append(
                f'{matrix}: Classification overview mismatch for {axis}: '
                f'declared={declared_classification.get(axis)}, actual={actual}'
            )

# A delegating case has no expectation of its own, so at least one row must supply one.
for test_id, (path, lineno) in sorted(delegating.items()):
    if test_id not in owned:
        errors.append(
            f'{path}:{lineno}: {test_id} delegates its expected result, but no '
            f'{matrix.name} row cites it'
        )

if not defined:
    errors.append('no case definitions were parsed')
if not seen_coverage:
    errors.append('no semantics rows were parsed')
if errors:
    print('\n'.join(errors), file=sys.stderr)
    raise SystemExit(1)
print(
    f'[check-docs] semantics matrix references resolved; '
    f'{len(delegating)} case(s) delegate their expected result'
)
PY
then
  fail 'semantics matrix references or expected-result delegation are invalid'
fi

legacy_paths=(
  "00-"$'顶层架构设计.md'
  "01-"$'协作规范.md'
  "ENV"'.md'
  "E2-"'ENV.md'
  "E3-"'ENV.md'
  "ECO-"'FINDINGS.md'
)
for legacy in "${legacy_paths[@]}"; do
  if rg -n --hidden --glob '!.git/**' --glob '!scripts/check-docs.sh' --fixed-strings "$legacy" .; then
    fail "legacy documentation path remains: $legacy"
  fi
done

if (( failures > 0 )); then
  printf '[check-docs] %d check(s) failed\n' "$failures" >&2
  exit 1
fi

note "PASS: PROGRESS.md ${progress_lines} lines/${progress_bytes} bytes; links, IDs, duplicates, archives, local identifiers, and legacy paths validated"
