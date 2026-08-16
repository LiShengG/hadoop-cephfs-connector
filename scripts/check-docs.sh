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

files = [pathlib.Path('docs/TEST-CASES.md'), pathlib.Path('docs/TEST-CASES-ECO.md')]
heading = re.compile(r'^## ([A-Z][A-Z0-9]*(?:-[A-Z0-9]+)+):\s+.+$')
required = ('Purpose', 'Preconditions', 'Steps', 'Expected result', 'Required environment')
definitions: dict[str, tuple[pathlib.Path, int]] = {}
errors: list[str] = []

for path in files:
    lines = path.read_text(encoding='utf-8').splitlines()
    positions: list[tuple[int, str]] = []
    for index, line in enumerate(lines):
        match = heading.match(line)
        if match:
            positions.append((index, match.group(1)))
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

legacy_paths=(
  "00-"$'顶层架构设计.md'
  "01-"$'协作规范.md'
  "TEST-"'PLAN.md'
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
