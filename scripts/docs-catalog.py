#!/usr/bin/env python3
"""Validate, query, edit, and serve the structured documentation catalog."""

import argparse
import copy
import datetime as _datetime
import fnmatch
import functools
import http.server
from http import HTTPStatus
import json
import os
from pathlib import Path, PurePosixPath
import re
import sys
import tempfile
from typing import Any, Dict, Iterable, List, Mapping, Optional, Sequence, Tuple
from urllib.parse import unquote, urlparse, urlsplit


SCHEMA = "hadoop-cephfs-docs/v1"
CATALOG_PATH = Path("docs/catalog.ndjson")

ALLOWED_KINDS = frozenset(
    {
        "meta",
        "project",
        "semantic",
        "baseline",
        "work",
        "risk",
        "evidence_request",
        "constraint",
        "readiness_area",
        "milestone",
        "run",
    }
)
CLASSIFICATIONS = frozenset({"MATCH", "DIFFERENT", "UNSUPPORTED", "UNKNOWN"})
COVERAGE_TYPES = frozenset({"UNIT", "CONTRACT", "CLUSTER", "SPIKE", "NONE"})
WORK_STATES = frozenset({"IN_PROGRESS", "BLOCKED"})
WORK_CATEGORIES = frozenset({"active", "blocker"})
RUN_STATUSES = frozenset({"PASS", "FAIL", "BLOCKED", "INFO"})
READINESS_STATUSES = frozenset(
    {"VERIFIED", "PARTIAL", "NOT_VERIFIED", "BLOCKED", "ACCEPTED_RISK"}
)
REQUIRED_RESOLVERS = frozenset({"record", "path", "case", "java", "url"})
EXPECTED_RESOLVER_PREFIXES = {
    "record": "record:",
    "path": "path:",
    "case": "case:",
    "java": "java:",
    "url": "url:",
}
BASIS_PATTERN = re.compile(r"^(?:HADOOP-SPEC|HDFS-\d+\.\d+\.\d+|PROJECT-ADR-\d{4})$")
WINDOWS_DRIVE_PATTERN = re.compile(r"^[A-Za-z]:")
LIMITATION_PATH_PATTERN = re.compile(
    r"^path:docs/KNOWN-LIMITATIONS\.md#lim-\d{3,4}(?:[-_].*)?$", re.IGNORECASE
)
ADR_PATH_PATTERN = re.compile(r"^path:docs/adr/(?P<number>\d{4})(?:[-./#].*)?$", re.IGNORECASE)
JAVA_GUARD_PATTERN = re.compile(r"^java:(?P<class>[^#]+)#(?P<method>[A-Za-z_$][\w$]*)$")
JAVA_SYMBOL_PATTERN = re.compile(
    r"^(?P<class>[^#]+)#(?P<method>[A-Za-z_$][\w$]*)$"
)
JAVA_CLASS_PATTERN = re.compile(
    r"^(?:[A-Za-z_$][\w$]*\.)*[A-Za-z_$][\w$]*$"
)
RECORD_ID_PATTERN = re.compile(r"^[A-Z][A-Z0-9]*(?:-[A-Z0-9]+)*$")
SHA_PATTERN = re.compile(r"^[0-9a-fA-F]{7,64}$")
SPIKE_GUARD_PATTERN = re.compile(r"^case:(?:SP|ECO-[A-Z]+)-\d+$")
REFERENCE_FIELDS = frozenset(
    {
        "guards",
        "partial_guards",
        "tracking",
        "evidence",
        "latest_report",
        "report",
        "projection",
        "source",
    }
)
MIGRATED_SEMANTIC_COUNTS = {
    "rename": 27,
    "create": 21,
    "delete": 10,
    "sync": 16,
    "append": 5,
    "mkdirs": 10,
}
EXPECTED_SEMANTIC_IDS_BY_AXIS = {
    axis: frozenset(
        "SEM-{}-{:03d}".format(axis.upper(), index)
        for index in range(1, count + 1)
    )
    for axis, count in MIGRATED_SEMANTIC_COUNTS.items()
}
CASE_HEADING_PATTERN = re.compile(
    r"^#{1,6}\s+((?:UT|CT|FN|REL-F|PERF|SOAK|COMPAT|SEC|OPS|NEG|SP|ECO-[A-Z]+)-\d+)\b"
)
ROOT_SERVE_FILES = frozenset(
    {"README.md", "PROGRESS.md", "EXPERIMENTS.md"}
)
_MISSING = object()


class CatalogError(Exception):
    """A user-facing catalog or command error."""


class CatalogValidationError(CatalogError):
    """One or more catalog invariants were violated."""

    def __init__(self, errors: Sequence[str]) -> None:
        self.errors = list(errors)
        super().__init__("catalog validation failed:\n" + "\n".join("- " + e for e in errors))


def _json_line(record: Mapping[str, Any]) -> str:
    # Loaded dicts preserve source key order, so updates touch only the intended
    # NDJSON line instead of mechanically reordering the whole catalog.
    return json.dumps(record, ensure_ascii=False, separators=(",", ":"))


def _require_string(
    record: Mapping[str, Any], key: str, label: str, errors: List[str]
) -> Optional[str]:
    value = record.get(key)
    if not isinstance(value, str) or not value.strip():
        errors.append("{}: '{}' must be a non-empty string".format(label, key))
        return None
    return value


def _require_string_list(
    record: Mapping[str, Any],
    key: str,
    label: str,
    errors: List[str],
    *,
    non_empty: bool = False,
) -> Optional[List[str]]:
    value = record.get(key)
    if not isinstance(value, list) or any(
        not isinstance(item, str) or not item.strip() for item in value
    ):
        errors.append("{}: '{}' must be an array of non-empty strings".format(label, key))
        return None
    if non_empty and not value:
        errors.append("{}: '{}' must not be empty".format(label, key))
        return None
    return value


def _require_sha(
    record: Mapping[str, Any], key: str, label: str, errors: List[str]
) -> Optional[str]:
    value = _require_string(record, key, label, errors)
    if value is not None and SHA_PATTERN.fullmatch(value) is None:
        errors.append("{}: '{}' must be a SHA-like string".format(label, key))
    return value


def _resolver_prefixes(
    meta: Mapping[str, Any], label: str, errors: List[str]
) -> Dict[str, str]:
    raw = meta.get("reference_resolvers")
    if not isinstance(raw, dict):
        errors.append("{}: 'reference_resolvers' must be an object".format(label))
        return {}

    missing = sorted(REQUIRED_RESOLVERS.difference(raw))
    if missing:
        errors.append(
            "{}: 'reference_resolvers' is missing {}".format(label, ", ".join(missing))
        )

    unexpected = sorted(set(raw).difference(REQUIRED_RESOLVERS))
    if unexpected:
        errors.append(
            "{}: 'reference_resolvers' has unsupported resolvers: {}".format(
                label, ", ".join(unexpected)
            )
        )

    prefixes: Dict[str, str] = {}
    for name, config in raw.items():
        if not isinstance(name, str) or not name:
            errors.append("{}: resolver names must be non-empty strings".format(label))
            continue
        if not isinstance(config, dict):
            errors.append("{}: resolver '{}' must be an object".format(label, name))
            continue
        prefix = config.get("prefix")
        if not isinstance(prefix, str) or not prefix:
            errors.append(
                "{}: resolver '{}.prefix' must be a non-empty string".format(label, name)
            )
            continue
        prefixes[name] = prefix
        expected_prefix = EXPECTED_RESOLVER_PREFIXES.get(name)
        if expected_prefix is not None and prefix != expected_prefix:
            errors.append(
                "{}: resolver '{}' must use prefix '{}'".format(
                    label, name, expected_prefix
                )
            )

    duplicate_prefixes = sorted(
        prefix for prefix in set(prefixes.values()) if list(prefixes.values()).count(prefix) > 1
    )
    if duplicate_prefixes:
        errors.append(
            "{}: resolver prefixes must be unique: {}".format(
                label, ", ".join(duplicate_prefixes)
            )
        )

    external = meta.get("external_reference_prefixes", [])
    if not isinstance(external, list) or any(
        not isinstance(prefix, str) or not prefix for prefix in external
    ):
        errors.append(
            "{}: 'external_reference_prefixes' must be an array of non-empty strings".format(
                label
            )
        )
    elif any(prefix not in prefixes.values() for prefix in external):
        errors.append(
            "{}: every external reference prefix must name a configured resolver prefix".format(
                label
            )
        )
    return prefixes


def _repeated_unquote(value: str) -> str:
    decoded = value
    for _ in range(4):
        next_value = unquote(decoded)
        if next_value == decoded:
            break
        decoded = next_value
    return decoded


def _repo_path_candidate(raw_path: str, repo_root: Path) -> Path:
    path_without_fragment = _repeated_unquote(
        raw_path.split("#", 1)[0].split("?", 1)[0]
    )
    if not path_without_fragment:
        raise ValueError("repository path must not be empty")
    if (
        path_without_fragment.startswith(("/", "\\", "~"))
        or WINDOWS_DRIVE_PATTERN.match(path_without_fragment)
        or "\\" in path_without_fragment
        or "\x00" in path_without_fragment
    ):
        raise ValueError("repository path must be relative: {}".format(raw_path))

    posix_path = PurePosixPath(path_without_fragment)
    if ".." in posix_path.parts:
        raise ValueError("repository path must not contain '..': {}".format(raw_path))

    candidate = (repo_root / Path(*posix_path.parts)).resolve()
    try:
        candidate.relative_to(repo_root.resolve())
    except ValueError:
        raise ValueError("repository path escapes the repository: {}".format(raw_path))
    return candidate


def _markdown_heading_anchors(path: Path) -> frozenset:
    anchors = set()
    occurrences: Dict[str, int] = {}
    heading = re.compile(r"^ {0,3}#{1,6}[ \t]+(?P<text>.*?)[ \t]*#*[ \t]*$")
    fence: Optional[str] = None
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.lstrip()
        if stripped.startswith(("```", "~~~")):
            marker = stripped[:3]
            if fence is None:
                fence = marker
            elif marker == fence:
                fence = None
            continue
        if fence is not None:
            continue
        match = heading.match(line)
        if match is None:
            continue
        text = re.sub(r"<[^>]*>", "", match.group("text"))
        slug = re.sub(r"[^\w\s-]", "", text.lower(), flags=re.UNICODE)
        slug = re.sub(r"\s+", "-", slug.strip())
        if not slug:
            continue
        occurrence = occurrences.get(slug, 0)
        occurrences[slug] = occurrence + 1
        anchors.add(slug if occurrence == 0 else "{}-{}".format(slug, occurrence))
    return frozenset(anchors)


def _validate_markdown_fragment(
    raw_path: str, *, candidate: Path, label: str, errors: List[str]
) -> None:
    if "#" not in raw_path or candidate.suffix.lower() != ".md":
        return
    fragment = _repeated_unquote(raw_path.split("#", 1)[1])
    if not fragment:
        errors.append("{}: Markdown path fragment must not be empty".format(label))
        return
    try:
        anchors = _markdown_heading_anchors(candidate)
    except (OSError, UnicodeError) as exc:
        errors.append("{}: cannot read Markdown target: {}".format(label, exc))
        return
    if fragment not in anchors:
        errors.append(
            "{}: Markdown heading fragment does not exist: #{}".format(
                label, fragment
            )
        )


def _validate_repo_path(
    raw_path: str,
    *,
    label: str,
    repo_root: Path,
    errors: List[str],
    require_exists: bool,
) -> None:
    try:
        candidate = _repo_path_candidate(raw_path, repo_root)
    except ValueError as exc:
        errors.append("{}: {}".format(label, exc))
        return
    if require_exists and not candidate.exists():
        errors.append("{}: repository path does not exist: {}".format(label, raw_path))
    elif require_exists and candidate.is_file():
        _validate_markdown_fragment(
            raw_path, candidate=candidate, label=label, errors=errors
        )


def _walk_strings(
    value: Any, path: str = "", containing_key: str = ""
) -> Iterable[Tuple[str, str, str]]:
    """Yield (location, key, string) for every string nested below value."""

    if isinstance(value, dict):
        for key, child in value.items():
            child_path = "{}.{}".format(path, key) if path else str(key)
            if isinstance(child, str):
                yield child_path, str(key), child
            else:
                yield from _walk_strings(child, child_path, str(key))
    elif isinstance(value, list):
        for index, child in enumerate(value):
            child_path = "{}[{}]".format(path, index)
            if isinstance(child, str):
                yield child_path, containing_key, child
            else:
                yield from _walk_strings(child, child_path, containing_key)


def _is_explicit_ref_field(key: str, kind: Optional[str] = None) -> bool:
    return (
        key in REFERENCE_FIELDS
        or key == "refs"
        or key.endswith("_refs")
        or (kind == "risk" and key == "required_evidence")
    )


def _path_reference_candidate(
    reference: str,
    *,
    path_prefix: str,
    label: str,
    repo_root: Path,
    errors: List[str],
) -> Optional[Path]:
    if not isinstance(reference, str) or not reference.startswith(path_prefix):
        errors.append("{}: expected a {} repository path reference".format(label, path_prefix))
        return None
    try:
        candidate = _repo_path_candidate(reference[len(path_prefix) :], repo_root)
    except ValueError as exc:
        errors.append("{}: {}".format(label, exc))
        return None
    if not candidate.is_file():
        errors.append("{}: referenced file does not exist: {}".format(label, reference))
        return None
    return candidate


def _load_case_ids(
    meta: Mapping[str, Any],
    *,
    path_prefix: str,
    label: str,
    repo_root: Path,
    errors: List[str],
) -> frozenset:
    resolvers = meta.get("reference_resolvers")
    case_config = resolvers.get("case") if isinstance(resolvers, dict) else None
    indexes = case_config.get("indexes") if isinstance(case_config, dict) else None
    if not isinstance(indexes, list) or not indexes or any(
        not isinstance(reference, str) or not reference for reference in indexes
    ):
        errors.append("{}: case.indexes must be a non-empty path-reference array".format(label))
        return frozenset()

    definitions: Dict[str, str] = {}
    for index, reference in enumerate(indexes):
        index_label = "{} reference_resolvers.case.indexes[{}]".format(label, index)
        candidate = _path_reference_candidate(
            reference,
            path_prefix=path_prefix,
            label=index_label,
            repo_root=repo_root,
            errors=errors,
        )
        if candidate is None:
            continue
        try:
            lines = candidate.read_text(encoding="utf-8").splitlines()
        except (OSError, UnicodeError) as exc:
            errors.append("{}: cannot read case index: {}".format(index_label, exc))
            continue
        for line_number, line in enumerate(lines, 1):
            match = CASE_HEADING_PATTERN.match(line)
            if not match:
                continue
            case_id = match.group(1)
            definition = "{}:{}".format(candidate.relative_to(repo_root), line_number)
            if case_id in definitions:
                errors.append(
                    "{}: duplicate case {} (also in {})".format(
                        definition, case_id, definitions[case_id]
                    )
                )
            else:
                definitions[case_id] = definition
    if not definitions:
        errors.append("{}: case.indexes contain no recognized case headings".format(label))
    return frozenset(definitions)


def _is_official_hadoop_336_url(reference: str, *, url_prefix: str) -> bool:
    if not reference.startswith(url_prefix):
        return False
    parsed = urlparse(reference[len(url_prefix) :])
    try:
        port = parsed.port
    except ValueError:
        return False
    if (
        parsed.scheme != "https"
        or parsed.username is not None
        or parsed.password is not None
        or port is not None
        or parsed.query
    ):
        return False
    decoded_path = _repeated_unquote(parsed.path)
    if "\\" in decoded_path or ".." in PurePosixPath(decoded_path).parts:
        return False
    if parsed.hostname == "hadoop.apache.org":
        return decoded_path.startswith("/docs/r3.3.6/")
    if parsed.hostname == "github.com":
        return bool(
            re.match(
                r"^/apache/hadoop/(?:blob|tree)/rel/release-3\.3\.6/[^/].*",
                decoded_path,
            )
        )
    return False


def _is_official_hadoop_test_java_url(reference: str, *, url_prefix: str) -> bool:
    if not _is_official_hadoop_336_url(reference, url_prefix=url_prefix):
        return False
    parsed = urlparse(reference[len(url_prefix) :])
    decoded_path = _repeated_unquote(parsed.path)
    return bool(
        re.match(
            r"^/apache/hadoop/blob/rel/release-3\.3\.6/[^/].*"
            r"/src/test/java/[^/].*\.java$",
            decoded_path,
        )
    )


def _validate_basis_source(
    basis_name: str,
    reference: str,
    *,
    path_prefix: str,
    url_prefix: str,
    label: str,
    errors: List[str],
) -> None:
    if basis_name in {"HADOOP-SPEC", "HDFS-3.3.6"}:
        if not _is_official_hadoop_336_url(reference, url_prefix=url_prefix):
            errors.append(
                "{}: {} source must be an official Hadoop 3.3.6 URL".format(
                    label, basis_name
                )
            )
        return
    match = re.fullmatch(r"PROJECT-ADR-(\d{4})", basis_name)
    if match is None:
        errors.append("{}: unsupported Basis source key: {}".format(label, basis_name))
        return
    number = match.group(1)
    expected = re.compile(
        r"^{}docs/adr/{}-[^/#?]+\.md(?:#[^\s]+)?$".format(
            re.escape(path_prefix), number
        )
    )
    if expected.fullmatch(reference) is None:
        errors.append(
            "{}: {} source must be the corresponding docs/adr/{}-*.md path".format(
                label, basis_name, number
            )
        )


def _load_java_index(
    meta: Mapping[str, Any],
    *,
    path_prefix: str,
    url_prefix: str,
    label: str,
    repo_root: Path,
    errors: List[str],
) -> Tuple[Dict[str, str], frozenset]:
    resolvers = meta.get("reference_resolvers")
    java_config = resolvers.get("java") if isinstance(resolvers, dict) else None
    if not isinstance(java_config, dict):
        errors.append("{}: java resolver must be an object".format(label))
        return {}, frozenset()

    raw_classes = java_config.get("classes")
    raw_symbols = java_config.get("symbols")
    if not isinstance(raw_classes, dict) or not raw_classes:
        errors.append("{}: java.classes must be a non-empty object".format(label))
        raw_classes = {}
    if not isinstance(raw_symbols, dict):
        errors.append("{}: java.symbols must be an object".format(label))
        raw_symbols = {}

    allowed_source_root = (repo_root / "hadoop-cephfs/src/test/java").resolve()
    class_sources: Dict[str, str] = {}
    for class_name, reference in raw_classes.items():
        class_label = "{} reference_resolvers.java.classes.{}".format(label, class_name)
        if not isinstance(class_name, str) or JAVA_CLASS_PATTERN.match(class_name) is None:
            errors.append("{}: invalid Java class key".format(class_label))
            continue
        candidate = _path_reference_candidate(
            reference,
            path_prefix=path_prefix,
            label=class_label,
            repo_root=repo_root,
            errors=errors,
        )
        if candidate is None:
            continue
        try:
            candidate.relative_to(allowed_source_root)
        except ValueError:
            errors.append("{}: Java class source is outside the test source root".format(class_label))
            continue
        if class_name.rsplit(".", 1)[-1] != candidate.stem:
            errors.append(
                "{}: Java class key must match source stem '{}'".format(
                    class_label, candidate.stem
                )
            )
            continue
        try:
            class_sources[class_name] = candidate.read_text(encoding="utf-8")
        except (OSError, UnicodeError) as exc:
            errors.append("{}: cannot read Java class source: {}".format(class_label, exc))

    symbols = set()
    for symbol, reference in raw_symbols.items():
        symbol_label = "{} reference_resolvers.java.symbols.{}".format(label, symbol)
        symbol_match = JAVA_SYMBOL_PATTERN.match(symbol) if isinstance(symbol, str) else None
        if (
            symbol_match is None
            or JAVA_CLASS_PATTERN.match(symbol_match.group("class")) is None
        ):
            errors.append("{}: invalid Class#method symbol key".format(symbol_label))
            continue
        if symbol_match.group("class") not in class_sources:
            errors.append(
                "{}: inherited Java symbol class has no valid local class mapping".format(
                    symbol_label
                )
            )
            continue
        if not isinstance(reference, str) or not _is_official_hadoop_test_java_url(
            reference, url_prefix=url_prefix
        ):
            errors.append(
                "{}: inherited symbol target must be an official Hadoop 3.3.6 "
                "test Java URL".format(symbol_label)
            )
            continue
        symbols.add(symbol)
    return class_sources, frozenset(symbols)


def _java_method_declared(source: str, method_name: str) -> bool:
    modifiers = r"(?:static|final|synchronized|abstract|native|strictfp)"
    declaration = re.compile(
        r"(?m)^[ \t]*(?:@[A-Za-z_$][\w.$]*(?:\([^\n]*\))?[ \t]+)*"
        r"(?:public|protected)[ \t]+(?:{}[ \t]+)*void[ \t]+{}[ \t]*\(".format(
            modifiers, re.escape(method_name)
        )
    )
    return declaration.search(source) is not None


def _validate_reference(
    reference: str,
    *,
    location: str,
    ids: frozenset,
    prefixes: Mapping[str, str],
    external_prefixes: frozenset,
    case_ids: frozenset,
    java_classes: Mapping[str, str],
    java_symbols: frozenset,
    repo_root: Path,
    errors: List[str],
) -> bool:
    for name, prefix in sorted(prefixes.items(), key=lambda item: len(item[1]), reverse=True):
        if not reference.startswith(prefix):
            continue
        payload = reference[len(prefix) :]
        if not payload:
            errors.append("{}: reference payload must not be empty: {}".format(location, reference))
            return True
        if name == "record":
            if payload not in ids:
                errors.append("{}: unresolved record reference: {}".format(location, reference))
        elif name == "path":
            _validate_repo_path(
                payload,
                label=location,
                repo_root=repo_root,
                errors=errors,
                require_exists=True,
            )
        elif name == "url":
            parsed = urlparse(payload)
            if parsed.scheme not in {"http", "https"} or not parsed.netloc:
                errors.append("{}: invalid url reference: {}".format(location, reference))
        elif name == "case":
            if payload not in case_ids:
                errors.append("{}: unknown case reference: {}".format(location, reference))
        elif name == "java":
            symbol_match = JAVA_SYMBOL_PATTERN.match(payload)
            if symbol_match is None:
                errors.append("{}: Java reference must be Class#method: {}".format(location, reference))
            elif payload not in java_symbols:
                class_name = symbol_match.group("class")
                method_name = symbol_match.group("method")
                source = java_classes.get(class_name)
                if source is None:
                    errors.append("{}: unknown Java class: {}".format(location, class_name))
                elif not _java_method_declared(source, method_name):
                    errors.append("{}: unknown Java method: {}".format(location, payload))
        elif prefix not in external_prefixes:
            errors.append(
                "{}: resolver '{}' is neither locally resolved nor declared external".format(
                    location, name
                )
            )
        return True

    if reference in ids:
        return True
    return False


def _validate_semantic(record: Mapping[str, Any], label: str, errors: List[str]) -> None:
    for key in ("axis", "api", "condition", "current"):
        _require_string(record, key, label, errors)
    if "expected" not in record:
        errors.append("{}: missing 'expected'".format(label))

    basis = _require_string_list(record, "basis", label, errors, non_empty=True)
    if basis is not None:
        if len(set(basis)) != len(basis):
            errors.append("{}: 'basis' must not contain duplicates".format(label))
        invalid_basis = sorted(value for value in basis if not BASIS_PATTERN.match(value))
        if invalid_basis:
            errors.append("{}: invalid basis: {}".format(label, ", ".join(invalid_basis)))

    classification = _require_string(record, "classification", label, errors)
    if classification is not None and classification not in CLASSIFICATIONS:
        errors.append("{}: invalid classification: {}".format(label, classification))

    coverage = _require_string_list(record, "coverage", label, errors, non_empty=True)
    if coverage is not None:
        if len(set(coverage)) != len(coverage):
            errors.append("{}: 'coverage' must not contain duplicates".format(label))
        invalid_coverage = sorted(set(coverage).difference(COVERAGE_TYPES))
        if invalid_coverage:
            errors.append("{}: invalid coverage: {}".format(label, ", ".join(invalid_coverage)))
        if "NONE" in coverage and len(coverage) != 1:
            errors.append("{}: coverage NONE cannot be combined with other values".format(label))

    guards = _require_string_list(record, "guards", label, errors)
    partial_guards = None
    if "partial_guards" in record:
        partial_guards = _require_string_list(
            record, "partial_guards", label, errors, non_empty=True
        )
        if partial_guards is not None and len(set(partial_guards)) != len(partial_guards):
            errors.append("{}: 'partial_guards' must not contain duplicates".format(label))
        if guards is not None and partial_guards is not None:
            overlap = sorted(set(guards).intersection(partial_guards))
            if overlap:
                errors.append(
                    "{}: guards and partial_guards overlap: {}".format(
                        label, ", ".join(overlap)
                    )
                )
    tracking = _require_string_list(record, "tracking", label, errors)

    for guard in (guards or []) + (partial_guards or []):
        match = JAVA_GUARD_PATTERN.match(guard)
        if match is not None and not match.group("method").startswith("test"):
            errors.append(
                "{}: semantic Java guard method must start with 'test': {}".format(
                    label, guard
                )
            )

    guard_roles = record.get("guard_roles", _MISSING)
    if guard_roles is not _MISSING:
        if not isinstance(guard_roles, dict) or not guard_roles:
            errors.append("{}: 'guard_roles' must be a non-empty object".format(label))
        else:
            guard_keys = {
                reference.split(":", 1)[1] if ":" in reference else reference
                for reference in (guards or []) + (partial_guards or [])
            }
            for role_key, role in guard_roles.items():
                if not isinstance(role_key, str) or not role_key:
                    errors.append("{}: guard_roles keys must be non-empty strings".format(label))
                elif role_key not in guard_keys:
                    errors.append(
                        "{}: guard_roles key has no matching guard: {}".format(
                            label, role_key
                        )
                    )
                if not isinstance(role, str) or not role.strip():
                    errors.append(
                        "{}: guard_roles.{} must be a non-empty string".format(
                            label, role_key
                        )
                    )
    if classification == "DIFFERENT" and tracking is not None:
        if not any(
            LIMITATION_PATH_PATTERN.match(reference) or ADR_PATH_PATTERN.match(reference)
            for reference in tracking
        ):
            errors.append("{}: DIFFERENT semantics require a limitation or ADR path".format(label))
    if classification == "UNKNOWN" and coverage is not None and coverage != ["NONE"]:
        errors.append("{}: UNKNOWN semantics require coverage [NONE]".format(label))

    if basis is not None and tracking is not None:
        for value in basis:
            if not value.startswith("PROJECT-ADR-"):
                continue
            adr_number = value.rsplit("-", 1)[-1]
            if not any(
                (match := ADR_PATH_PATTERN.match(reference))
                and match.group("number") == adr_number
                for reference in tracking
            ):
                errors.append(
                    "{}: basis {} requires the corresponding ADR path in tracking".format(
                        label, value
                    )
                )

    if guards is None or coverage is None:
        return
    java_guards = []
    for guard in guards:
        match = JAVA_GUARD_PATTERN.match(guard)
        if match:
            java_guards.append(match.groupdict())
    if set(coverage).intersection({"UNIT", "CONTRACT", "CLUSTER"}) and not java_guards:
        errors.append("{}: automated coverage requires a java:Class#method guard".format(label))
    if "UNIT" in coverage and not any(
        guard["class"].split(".")[-1].startswith("Test") for guard in java_guards
    ):
        errors.append("{}: UNIT coverage requires a java:Test*#method guard".format(label))
    if "CONTRACT" in coverage and not any(
        guard["class"].split(".")[-1].startswith("ITestCephContract")
        for guard in java_guards
    ):
        errors.append(
            "{}: CONTRACT coverage requires a java:ITestCephContract*#method guard".format(
                label
            )
        )
    if "CLUSTER" in coverage and not any(
        guard["class"].split(".")[-1].startswith("ITest") for guard in java_guards
    ):
        errors.append("{}: CLUSTER coverage requires a java:ITest*#method guard".format(label))
    if "SPIKE" in coverage and not any(SPIKE_GUARD_PATTERN.match(guard) for guard in guards):
        errors.append("{}: SPIKE coverage requires a case:SP-* or case:ECO-* guard".format(label))


def _validate_run(record: Mapping[str, Any], label: str, errors: List[str]) -> None:
    date = _require_string(record, "date", label, errors)
    if date is not None:
        try:
            parsed_date = _datetime.date.fromisoformat(date)
        except ValueError:
            errors.append("{}: 'date' must be an ISO calendar date".format(label))
        else:
            if parsed_date > _datetime.date.today():
                errors.append("{}: run date must not be in the future".format(label))
    _require_string(record, "title", label, errors)
    status = _require_string(record, "status", label, errors)
    if status is not None and status not in RUN_STATUSES:
        errors.append("{}: invalid run status: {}".format(label, status))
    _require_string_list(
        record,
        "commands",
        label,
        errors,
        non_empty=record.get("revision_unknown") is not True,
    )
    for key in ("details", "integrity", "cleanup", "evidence"):
        _require_string_list(record, key, label, errors)
    _require_string(record, "result", label, errors)
    commit = record.get("commit", _MISSING)
    worktree_base = record.get("worktree_base", _MISSING)
    dirty = record.get("dirty", _MISSING)
    revision_unknown = record.get("revision_unknown", _MISSING)
    descriptors = sum(
        (
            commit is not _MISSING,
            worktree_base is not _MISSING or dirty is not _MISSING,
            revision_unknown is not _MISSING,
        )
    )
    if descriptors != 1:
        errors.append(
            "{}: run requires exactly one revision descriptor: commit; "
            "worktree_base + dirty:true; or revision_unknown:true".format(label)
        )
    if commit is not _MISSING:
        if not isinstance(commit, str) or SHA_PATTERN.fullmatch(commit) is None:
            errors.append("{}: 'commit' must be a SHA-like string".format(label))
    if worktree_base is not _MISSING or dirty is not _MISSING:
        if not isinstance(worktree_base, str) or SHA_PATTERN.fullmatch(worktree_base) is None:
            errors.append("{}: 'worktree_base' must be a SHA-like string".format(label))
        if dirty is not True:
            errors.append("{}: worktree revision requires dirty:true".format(label))
    if revision_unknown is not _MISSING and revision_unknown is not True:
        errors.append("{}: 'revision_unknown' must be true".format(label))


def _validate_project(record: Mapping[str, Any], label: str, errors: List[str]) -> None:
    updated = _require_string(record, "updated", label, errors)
    if updated is not None:
        try:
            _datetime.date.fromisoformat(updated)
        except ValueError:
            errors.append("{}: 'updated' must be an ISO calendar date".format(label))
    _require_sha(record, "commit", label, errors)
    _require_string(record, "active_milestone", label, errors)


def _validate_baseline(record: Mapping[str, Any], label: str, errors: List[str]) -> None:
    for key in ("environment", "result", "report"):
        _require_string(record, key, label, errors)
    _require_sha(record, "commit", label, errors)


def _validate_risk(record: Mapping[str, Any], label: str, errors: List[str]) -> None:
    for key in ("name", "impact"):
        _require_string(record, key, label, errors)
    _require_string_list(record, "required_evidence", label, errors, non_empty=True)
    _require_string_list(record, "tracking", label, errors, non_empty=True)
    for optional_key in ("evidence_expression", "required_policy"):
        if optional_key in record:
            _require_string(record, optional_key, label, errors)


def _validate_work(record: Mapping[str, Any], label: str, errors: List[str]) -> None:
    category = _require_string(record, "category", label, errors)
    if category is not None and category not in WORK_CATEGORIES:
        errors.append("{}: invalid work category: {}".format(label, category))
    state = _require_string(record, "state", label, errors)
    if state is not None and state not in WORK_STATES:
        errors.append("{}: invalid work state: {}".format(label, state))
    _require_string_list(record, "tracking", label, errors, non_empty=True)
    if category == "active":
        for key in ("current_finding", "next_action", "exit_criteria"):
            _require_string(record, key, label, errors)
    elif category == "blocker":
        for key in ("blocker", "resolution_condition"):
            _require_string(record, key, label, errors)


def _validate_readiness_area(
    record: Mapping[str, Any], label: str, errors: List[str]
) -> None:
    for key in ("area", "required_evidence", "open_items"):
        _require_string(record, key, label, errors)
    status = _require_string(record, "status", label, errors)
    if status is not None and status not in READINESS_STATUSES:
        errors.append("{}: invalid readiness status: {}".format(label, status))
    _require_string_list(record, "latest_report", label, errors)


def _validate_evidence_request(
    record: Mapping[str, Any], label: str, errors: List[str]
) -> None:
    for key in (
        "checkpoint",
        "owner",
        "required_execution",
        "result_needed",
        "destination",
    ):
        _require_string(record, key, label, errors)
    if "destination_refs" in record:
        _require_string_list(record, "destination_refs", label, errors, non_empty=True)


def _validate_constraint(record: Mapping[str, Any], label: str, errors: List[str]) -> None:
    for key in ("name", "required_handling", "source"):
        _require_string(record, key, label, errors)


def _validate_milestone(record: Mapping[str, Any], label: str, errors: List[str]) -> None:
    for key in ("milestone", "requirement"):
        _require_string(record, key, label, errors)
    _require_string_list(record, "tracking", label, errors, non_empty=True)


def _validate_canonical_scopes(
    meta: Mapping[str, Any],
    *,
    record_ids: Sequence[str],
    label: str,
    path_prefix: str,
    errors: List[str],
) -> None:
    raw_scopes = meta.get("canonical_scopes")
    if not isinstance(raw_scopes, list) or not raw_scopes:
        errors.append("{}: 'canonical_scopes' must be a non-empty array".format(label))
        return

    available = frozenset(record_ids)
    covered = set()
    scope_owners: Dict[str, List[int]] = {}
    for index, scope in enumerate(raw_scopes):
        scope_label = "{} canonical_scopes[{}]".format(label, index)
        if not isinstance(scope, dict):
            errors.append("{}: scope must be an object".format(scope_label))
            continue
        selector_text = scope.get("records")
        projection = scope.get("projection")
        if not isinstance(selector_text, str) or not selector_text.strip():
            errors.append("{}: 'records' must be a non-empty selector string".format(scope_label))
            continue
        if not isinstance(projection, str) or not projection.startswith(path_prefix):
            errors.append("{}: 'projection' must be a path reference".format(scope_label))
        selectors = [selector.strip() for selector in selector_text.split(",")]
        if any(not selector for selector in selectors):
            errors.append("{}: selectors must not be empty".format(scope_label))
            continue
        scope_matches = set()
        for selector in selectors:
            matches = {record_id for record_id in available if fnmatch.fnmatchcase(record_id, selector)}
            if not matches:
                errors.append("{}: selector matches no records: {}".format(scope_label, selector))
            scope_matches.update(matches)
        covered.update(scope_matches)
        for record_id in scope_matches:
            scope_owners.setdefault(record_id, []).append(index)

    multiply_owned = sorted(
        (record_id, owners)
        for record_id, owners in scope_owners.items()
        if len(owners) > 1
    )
    if multiply_owned:
        errors.append(
            "{}: records matched by multiple canonical_scopes: {}".format(
                label,
                ", ".join(
                    "{} ({})".format(
                        record_id, ", ".join(str(owner) for owner in owners)
                    )
                    for record_id, owners in multiply_owned
                ),
            )
        )

    uncovered = sorted(available.difference(covered))
    if uncovered:
        errors.append("{}: records outside canonical_scopes: {}".format(label, ", ".join(uncovered)))


def validate_records(
    records: Sequence[Mapping[str, Any]],
    *,
    repo_root: Path,
    line_numbers: Optional[Sequence[int]] = None,
) -> None:
    errors: List[str] = []
    if not records:
        raise CatalogValidationError(["catalog is empty"])

    labels = [
        "line {}".format(line_numbers[index] if line_numbers else index + 1)
        for index in range(len(records))
    ]
    first = records[0]
    if first.get("kind") != "meta":
        errors.append("{}: first record must have kind 'meta'".format(labels[0]))
    if first.get("schema") != SCHEMA:
        errors.append("{}: unsupported or missing schema; expected '{}'".format(labels[0], SCHEMA))

    if "migration_baseline_commit" in first:
        _require_sha(first, "migration_baseline_commit", labels[0], errors)
    historical_revision_unknown_runs = first.get(
        "historical_revision_unknown_runs", []
    )
    if not isinstance(historical_revision_unknown_runs, list) or any(
        not isinstance(record_id, str) or not record_id
        for record_id in historical_revision_unknown_runs
    ):
        errors.append(
            "{}: 'historical_revision_unknown_runs' must be a string array".format(
                labels[0]
            )
        )
        historical_revision_unknown_runs = []
    elif len(set(historical_revision_unknown_runs)) != len(
        historical_revision_unknown_runs
    ):
        errors.append(
            "{}: 'historical_revision_unknown_runs' must not contain duplicates".format(
                labels[0]
            )
        )
    has_semantics = any(
        isinstance(record, dict) and record.get("kind") == "semantic"
        for record in records
    )
    basis_sources = first.get("basis_sources")
    if has_semantics and (not isinstance(basis_sources, dict) or not basis_sources):
        errors.append(
            "{}: semantic records require 'basis_sources' as a non-empty object".format(
                labels[0]
            )
        )
    elif basis_sources is not None and not isinstance(basis_sources, dict):
        errors.append("{}: 'basis_sources' must be an object".format(labels[0]))
    if isinstance(basis_sources, dict):
        for basis_name, references in basis_sources.items():
            if not isinstance(basis_name, str) or not BASIS_PATTERN.match(basis_name):
                errors.append("{}: invalid basis_sources key: {}".format(labels[0], basis_name))
            if not isinstance(references, list) or not references or any(
                not isinstance(reference, str) or not reference for reference in references
            ):
                errors.append(
                    "{}: basis_sources.{} must be a non-empty string array".format(
                        labels[0], basis_name
                    )
                )

    axis_basis_sources = first.get("axis_basis_sources", _MISSING)
    if axis_basis_sources is not _MISSING and (
        not isinstance(axis_basis_sources, dict) or not axis_basis_sources
    ):
        errors.append("{}: 'axis_basis_sources' must be a non-empty object".format(labels[0]))
    if isinstance(axis_basis_sources, dict):
        for axis, axis_sources in axis_basis_sources.items():
            axis_label = "{} axis_basis_sources.{}".format(labels[0], axis)
            if axis not in MIGRATED_SEMANTIC_COUNTS:
                errors.append(
                    "{}: axis is not in the migrated semantic inventory".format(
                        axis_label
                    )
                )
            if not isinstance(axis_sources, dict) or not axis_sources:
                errors.append("{} must be a non-empty object".format(axis_label))
                continue
            for basis_name, references in axis_sources.items():
                basis_label = "{}.{}".format(axis_label, basis_name)
                if not isinstance(basis_name, str) or not BASIS_PATTERN.match(basis_name):
                    errors.append("{}: invalid Basis key".format(basis_label))
                if not isinstance(references, list) or not references or any(
                    not isinstance(reference, str) or not reference for reference in references
                ):
                    errors.append("{} must be a non-empty string array".format(basis_label))

    prefixes = _resolver_prefixes(first, labels[0], errors)
    path_prefix = prefixes.get("path", "path:")
    url_prefix = prefixes.get("url", "url:")
    if isinstance(basis_sources, dict):
        for basis_name, references in basis_sources.items():
            if not isinstance(basis_name, str) or not isinstance(references, list):
                continue
            for index, reference in enumerate(references):
                if isinstance(reference, str) and reference:
                    _validate_basis_source(
                        basis_name,
                        reference,
                        path_prefix=path_prefix,
                        url_prefix=url_prefix,
                        label="{} basis_sources.{}[{}]".format(
                            labels[0], basis_name, index
                        ),
                        errors=errors,
                    )
    if isinstance(axis_basis_sources, dict):
        for axis, axis_sources in axis_basis_sources.items():
            if not isinstance(axis_sources, dict):
                continue
            for basis_name, references in axis_sources.items():
                if not isinstance(basis_name, str) or not isinstance(references, list):
                    continue
                for index, reference in enumerate(references):
                    if isinstance(reference, str) and reference:
                        _validate_basis_source(
                            basis_name,
                            reference,
                            path_prefix=path_prefix,
                            url_prefix=url_prefix,
                            label="{} axis_basis_sources.{}.{}[{}]".format(
                                labels[0], axis, basis_name, index
                            ),
                            errors=errors,
                        )
    external_raw = first.get("external_reference_prefixes", [])
    external_prefixes = frozenset(external_raw if isinstance(external_raw, list) else [])

    ids_seen: Dict[str, str] = {}
    ids: List[str] = []
    for record, label in zip(records, labels):
        if not isinstance(record, dict):
            errors.append("{}: each NDJSON value must be an object".format(label))
            continue
        record_id = _require_string(record, "id", label, errors)
        kind = _require_string(record, "kind", label, errors)
        if record_id is not None:
            if RECORD_ID_PATTERN.match(record_id) is None:
                errors.append("{}: invalid id format: {}".format(label, record_id))
            if record_id in ids_seen:
                errors.append(
                    "{}: duplicate id '{}' (first defined at {})".format(
                        label, record_id, ids_seen[record_id]
                    )
                )
            else:
                ids_seen[record_id] = label
                ids.append(record_id)
        if kind is not None and kind not in ALLOWED_KINDS:
            errors.append("{}: invalid kind: {}".format(label, kind))
        if kind == "meta" and record is not first:
            errors.append("{}: meta record is only allowed on the first line".format(label))
        elif kind == "project":
            _validate_project(record, label, errors)
        elif kind == "semantic":
            _validate_semantic(record, label, errors)
            axis = record.get("axis")
            if isinstance(axis, str) and axis not in MIGRATED_SEMANTIC_COUNTS:
                errors.append("{}: semantic axis is not migrated: {}".format(label, axis))
            elif (
                isinstance(axis, str)
                and record_id is not None
                and record_id not in EXPECTED_SEMANTIC_IDS_BY_AXIS[axis]
            ):
                count = MIGRATED_SEMANTIC_COUNTS[axis]
                errors.append(
                    "{}: semantic id '{}' does not match axis '{}'; expected range "
                    "SEM-{}-001..{:03d}".format(
                        label, record_id, axis, axis.upper(), count
                    )
                )
        elif kind == "baseline":
            _validate_baseline(record, label, errors)
        elif kind == "work":
            _validate_work(record, label, errors)
        elif kind == "risk":
            _validate_risk(record, label, errors)
        elif kind == "evidence_request":
            _validate_evidence_request(record, label, errors)
        elif kind == "constraint":
            _validate_constraint(record, label, errors)
        elif kind == "readiness_area":
            _validate_readiness_area(record, label, errors)
        elif kind == "milestone":
            _validate_milestone(record, label, errors)
        elif kind == "run":
            _validate_run(record, label, errors)

    semantic_ids_by_axis = {
        axis: frozenset(
            record.get("id")
            for record in records
            if record.get("kind") == "semantic"
            and record.get("axis") == axis
            and isinstance(record.get("id"), str)
        )
        for axis in MIGRATED_SEMANTIC_COUNTS
    }
    for axis, count in MIGRATED_SEMANTIC_COUNTS.items():
        expected_ids = EXPECTED_SEMANTIC_IDS_BY_AXIS[axis]
        observed_ids = semantic_ids_by_axis[axis]
        if observed_ids == expected_ids:
            continue
        missing = sorted(expected_ids.difference(observed_ids))
        unexpected = sorted(observed_ids.difference(expected_ids))
        details = []
        if missing:
            details.append("missing {}".format(", ".join(missing)))
        if unexpected:
            details.append("unexpected {}".format(", ".join(unexpected)))
        errors.append(
            "semantic inventory for '{}' must be SEM-{}-001..{:03d}: {}".format(
                axis, axis.upper(), count, "; ".join(details)
            )
        )

    observed_revision_unknown_runs = {
        record.get("id")
        for record in records
        if record.get("kind") == "run"
        and record.get("revision_unknown") is True
        and isinstance(record.get("id"), str)
    }
    declared_revision_unknown_runs = set(historical_revision_unknown_runs)
    if observed_revision_unknown_runs != declared_revision_unknown_runs:
        missing = sorted(
            observed_revision_unknown_runs.difference(declared_revision_unknown_runs)
        )
        stale = sorted(
            declared_revision_unknown_runs.difference(observed_revision_unknown_runs)
        )
        details = []
        if missing:
            details.append("undeclared {}".format(", ".join(missing)))
        if stale:
            details.append("not revision_unknown runs {}".format(", ".join(stale)))
        errors.append(
            "{}: historical revision_unknown run inventory mismatch: {}".format(
                labels[0], "; ".join(details)
            )
        )

    non_meta_ids = [
        record["id"]
        for record in records
        if record.get("kind") != "meta" and isinstance(record.get("id"), str)
    ]
    _validate_canonical_scopes(
        first,
        record_ids=non_meta_ids,
        label=labels[0],
        path_prefix=prefixes.get("path", "path:"),
        errors=errors,
    )

    case_ids = _load_case_ids(
        first,
        path_prefix=prefixes.get("path", "path:"),
        label=labels[0],
        repo_root=repo_root,
        errors=errors,
    )
    java_classes, java_symbols = _load_java_index(
        first,
        path_prefix=prefixes.get("path", "path:"),
        url_prefix=prefixes.get("url", "url:"),
        label=labels[0],
        repo_root=repo_root,
        errors=errors,
    )

    for record, label in zip(records, labels):
        if record.get("kind") != "semantic" or not isinstance(record.get("basis"), list):
            continue
        axis = record.get("axis")
        axis_sources = (
            axis_basis_sources.get(axis)
            if isinstance(axis_basis_sources, dict) and isinstance(axis, str)
            else None
        )
        for basis_name in record["basis"]:
            if not isinstance(basis_name, str):
                continue
            if isinstance(axis_basis_sources, dict):
                if isinstance(axis_sources, dict) and basis_name in axis_sources:
                    continue
                if (
                    basis_name.startswith("PROJECT-ADR-")
                    and isinstance(basis_sources, dict)
                    and basis_name in basis_sources
                ):
                    continue
                errors.append(
                    "{}: basis '{}' has no entry in meta.axis_basis_sources.{}".format(
                        label, basis_name, axis
                    )
                )
            elif not isinstance(basis_sources, dict) or basis_name not in basis_sources:
                errors.append(
                    "{}: basis '{}' has no entry in meta.basis_sources".format(
                        label, basis_name
                    )
                )

    if isinstance(axis_basis_sources, dict):
        for axis in MIGRATED_SEMANTIC_COUNTS:
            axis_sources = axis_basis_sources.get(axis)
            used_basis = {
                basis_name
                for record in records
                if record.get("kind") == "semantic"
                and record.get("axis") == axis
                and isinstance(record.get("basis"), list)
                for basis_name in record["basis"]
                if isinstance(basis_name, str)
                and not basis_name.startswith("PROJECT-ADR-")
            }
            configured_basis = set(axis_sources) if isinstance(axis_sources, dict) else set()
            if configured_basis != used_basis:
                missing = sorted(used_basis.difference(configured_basis))
                unused = sorted(configured_basis.difference(used_basis))
                details = []
                if missing:
                    details.append("missing {}".format(", ".join(missing)))
                if unused:
                    details.append("unused {}".format(", ".join(unused)))
                errors.append(
                    "{} axis_basis_sources.{} keys must equal the used non-project "
                    "Basis set: {}".format(labels[0], axis, "; ".join(details))
                )

    frozen_ids = frozenset(ids)
    path_fields = {"path", "report", "latest_report", "source_path", "destination_path"}
    for record, label in zip(records, labels):
        for key, value in record.items():
            if not _is_explicit_ref_field(str(key), record.get("kind")):
                continue
            if isinstance(value, str):
                valid_shape = bool(value)
            else:
                valid_shape = isinstance(value, list) and all(
                    isinstance(reference, str) and bool(reference) for reference in value
                )
            if not valid_shape:
                errors.append(
                    "{}: '{}' must be a reference string or an array of reference strings".format(
                        label, key
                    )
                )
        for location, key, value in _walk_strings(record):
            full_location = "{} {}".format(label, location)
            if record.get("kind") == "meta" and (
                key == "external_reference_prefixes"
                or (key == "prefix" and location.startswith("reference_resolvers."))
            ):
                # These are the literal resolver prefixes, not empty references.
                continue
            recognized = _validate_reference(
                value,
                location=full_location,
                ids=frozen_ids,
                prefixes=prefixes,
                external_prefixes=external_prefixes,
                case_ids=case_ids,
                java_classes=java_classes,
                java_symbols=java_symbols,
                repo_root=repo_root,
                errors=errors,
            )
            if _is_explicit_ref_field(key, record.get("kind")) and not recognized:
                errors.append("{}: unresolved or unapproved reference: {}".format(full_location, value))
            if key in path_fields or key.endswith("_path"):
                _validate_repo_path(
                    value,
                    label=full_location,
                    repo_root=repo_root,
                    errors=errors,
                    require_exists=False,
                )

    if errors:
        raise CatalogValidationError(errors)


def load_catalog(path: Path, *, repo_root: Optional[Path] = None) -> List[Dict[str, Any]]:
    records: List[Dict[str, Any]] = []
    line_numbers: List[int] = []
    parse_errors: List[str] = []
    try:
        text = path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as exc:
        raise CatalogError("cannot read {}: {}".format(path, exc)) from exc

    for line_number, raw_line in enumerate(text.splitlines(), 1):
        if not raw_line.strip():
            parse_errors.append("line {}: blank lines are not allowed in NDJSON".format(line_number))
            continue
        try:
            value = json.loads(raw_line)
        except json.JSONDecodeError as exc:
            parse_errors.append(
                "line {}: invalid JSON at column {}: {}".format(
                    line_number, exc.colno, exc.msg
                )
            )
            continue
        if not isinstance(value, dict):
            parse_errors.append("line {}: each NDJSON value must be an object".format(line_number))
            continue
        records.append(value)
        line_numbers.append(line_number)
    if parse_errors:
        raise CatalogValidationError(parse_errors)

    root = (repo_root or path.resolve().parent.parent).resolve()
    validate_records(records, repo_root=root, line_numbers=line_numbers)
    return records


def _field_value(record: Mapping[str, Any], dotted_name: str) -> Any:
    value: Any = record
    for component in dotted_name.split("."):
        if isinstance(value, dict) and component in value:
            value = value[component]
        else:
            return _MISSING
    return value


def _parse_assignment(raw: str, option: str) -> Tuple[str, Any]:
    if "=" not in raw:
        raise CatalogError("{} requires field=value: {}".format(option, raw))
    field, raw_value = raw.split("=", 1)
    if not field or any(not part for part in field.split(".")):
        raise CatalogError("{} has an invalid field name: {}".format(option, field))
    try:
        value = json.loads(raw_value)
    except json.JSONDecodeError:
        value = raw_value
    return field, value


def filter_records(
    records: Iterable[Mapping[str, Any]],
    *,
    kind: Optional[str] = None,
    where: Sequence[Tuple[str, Any]] = (),
) -> List[Mapping[str, Any]]:
    def matches(record: Mapping[str, Any]) -> bool:
        if kind is not None and record.get("kind") != kind:
            return False
        for field, expected in where:
            actual = _field_value(record, field)
            if isinstance(actual, list) and not isinstance(expected, list):
                if expected not in actual:
                    return False
            elif actual != expected:
                return False
        return True

    return [record for record in records if matches(record)]


def _set_field(record: Dict[str, Any], dotted_name: str, value: Any) -> None:
    target: Dict[str, Any] = record
    components = dotted_name.split(".")
    for component in components[:-1]:
        child = target.get(component)
        if child is None:
            child = {}
            target[component] = child
        if not isinstance(child, dict):
            raise CatalogError(
                "cannot set '{}': '{}' is not an object".format(dotted_name, component)
            )
        target = child
    target[components[-1]] = value


def _find_record(records: Sequence[Dict[str, Any]], record_id: str) -> Dict[str, Any]:
    for record in records:
        if record.get("id") == record_id:
            return record
    raise CatalogError("record not found: {}".format(record_id))


def write_catalog_atomic(path: Path, records: Sequence[Mapping[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    mode = path.stat().st_mode & 0o777 if path.exists() else 0o644
    temporary_name: Optional[str] = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            newline="\n",
            dir=str(path.parent),
            prefix=".{}-".format(path.name),
            suffix=".tmp",
            delete=False,
        ) as temporary:
            temporary_name = temporary.name
            for record in records:
                temporary.write(_json_line(record) + "\n")
            temporary.flush()
            os.fsync(temporary.fileno())
        os.chmod(temporary_name, mode)
        os.replace(temporary_name, path)
        temporary_name = None
        try:
            directory_fd = os.open(str(path.parent), os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
        except OSError:
            directory_fd = None
        if directory_fd is not None:
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
    finally:
        if temporary_name is not None:
            try:
                os.unlink(temporary_name)
            except FileNotFoundError:
                pass


def _read_run_file(path: Path) -> Dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except OSError as exc:
        raise CatalogError("cannot read run file {}: {}".format(path, exc)) from exc
    except json.JSONDecodeError as exc:
        raise CatalogError(
            "invalid run JSON in {} at line {}, column {}: {}".format(
                path, exc.lineno, exc.colno, exc.msg
            )
        ) from exc
    if not isinstance(value, dict):
        raise CatalogError("run file must contain one JSON object")
    if value.get("kind") != "run":
        raise CatalogError("run file must contain a record with kind 'run'")
    return value


def _catalog_for_args(args: argparse.Namespace, repo_root: Path) -> Path:
    if args.catalog is None:
        return repo_root / CATALOG_PATH
    return Path(args.catalog).resolve()


class CatalogRequestHandler(http.server.SimpleHTTPRequestHandler):
    """Static handler constrained to documentation and test-evidence files."""

    def __init__(self, *args: Any, repo_root: Path, **kwargs: Any) -> None:
        self.repo_root = repo_root.resolve()
        super().__init__(*args, directory=str(self.repo_root), **kwargs)

    def _decoded_segments(self) -> Optional[List[str]]:
        raw_path = urlsplit(self.path).path
        # Repeated decoding rejects nested encodings of dot segments while a
        # normal filename remains unchanged after the first pass.
        decoded = _repeated_unquote(raw_path)
        if (
            not decoded.startswith("/")
            or decoded.startswith("//")
            or "\\" in decoded
            or "\x00" in decoded
        ):
            return None
        segments = decoded[1:].split("/")
        if segments and segments[-1] == "":
            segments.pop()
        if not segments or any(
            not segment or segment in {".", ".."} or segment.startswith(".")
            for segment in segments
        ):
            return None
        return segments

    def _has_symlink_component(self, segments: Sequence[str]) -> bool:
        candidate = self.repo_root
        for segment in segments:
            candidate = candidate / segment
            if candidate.is_symlink():
                return True
        return False

    def _is_allowed_request(self) -> bool:
        segments = self._decoded_segments()
        if segments is None or self._has_symlink_component(segments):
            return False

        unresolved = self.repo_root.joinpath(*segments)
        candidate = unresolved.resolve()
        try:
            candidate.relative_to(self.repo_root)
        except ValueError:
            return False

        allowed_root: Optional[Path] = None
        if segments[0] == "docs":
            allowed_root = (self.repo_root / "docs").resolve()
        elif segments[:4] == ["hadoop-cephfs", "src", "test", "java"]:
            allowed_root = (self.repo_root / "hadoop-cephfs/src/test/java").resolve()
        elif len(segments) == 1 and segments[0] in ROOT_SERVE_FILES:
            allowed_root = self.repo_root
        if allowed_root is None:
            return False
        try:
            candidate.relative_to(allowed_root)
        except ValueError:
            return False

        # SimpleHTTPRequestHandler implicitly opens an index file for a
        # directory request, so validate that implicit target too.
        if candidate.is_dir():
            for index_name in ("index.html", "index.htm"):
                index = unresolved / index_name
                if not index.exists():
                    continue
                if index.is_symlink():
                    return False
                try:
                    index.resolve().relative_to(allowed_root)
                except ValueError:
                    return False
                break
        return True

    def _reject_disallowed(self) -> bool:
        if self._is_allowed_request():
            return False
        self.send_error(HTTPStatus.FORBIDDEN, "Path is outside the documentation allowlist")
        return True

    def do_GET(self) -> None:
        if not self._reject_disallowed():
            super().do_GET()

    def do_HEAD(self) -> None:
        if not self._reject_disallowed():
            super().do_HEAD()


def _serve(repo_root: Path, bind: str, port: int) -> None:
    handler = functools.partial(CatalogRequestHandler, repo_root=repo_root)
    with http.server.ThreadingHTTPServer((bind, port), handler) as server:
        server.daemon_threads = True
        host, actual_port = server.server_address[:2]
        print("Viewer: http://{}:{}/docs/viewer/".format(host, actual_port), flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Validate, query, edit, and serve docs/catalog.ndjson"
    )
    parser.add_argument(
        "--catalog",
        metavar="PATH",
        help="catalog path (default: docs/catalog.ndjson below the repository root)",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    subparsers.add_parser("validate", help="validate the complete catalog")

    show = subparsers.add_parser("show", help="print exactly one record by ID")
    show.add_argument("id")

    query = subparsers.add_parser("query", help="print matching records as NDJSON")
    query.add_argument("--kind", choices=sorted(ALLOWED_KINDS))
    query.add_argument(
        "--where", action="append", default=[], metavar="FIELD=VALUE", help="AND filter; repeatable"
    )

    update = subparsers.add_parser("update", help="preview or atomically update one record")
    update.add_argument("id")
    update.add_argument("--set", action="append", required=True, metavar="FIELD=VALUE")
    update.add_argument("--write", action="store_true", help="write after full-catalog validation")

    add_run = subparsers.add_parser("add-run", help="preview or atomically add one run record")
    add_run.add_argument("file", metavar="FILE")
    add_run.add_argument("--write", action="store_true", help="write after full-catalog validation")

    serve = subparsers.add_parser("serve", help="serve the repository as static HTTP")
    serve.add_argument("--bind", default="127.0.0.1")
    serve.add_argument("--port", type=int, default=8000)
    return parser


def run(args: argparse.Namespace, *, repo_root: Path) -> int:
    repo_root = repo_root.resolve()
    if args.command == "serve":
        if not 0 <= args.port <= 65535:
            raise CatalogError("port must be between 0 and 65535")
        _serve(repo_root, args.bind, args.port)
        return 0

    catalog_path = _catalog_for_args(args, repo_root)
    records = load_catalog(catalog_path, repo_root=repo_root)

    if args.command == "validate":
        print(_json_line({"records": len(records), "schema": SCHEMA, "status": "valid"}))
        return 0

    if args.command == "show":
        print(_json_line(_find_record(records, args.id)))
        return 0

    if args.command == "query":
        where = [_parse_assignment(raw, "--where") for raw in args.where]
        for record in filter_records(records, kind=args.kind, where=where):
            print(_json_line(record))
        return 0

    if args.command == "update":
        updated_records = copy.deepcopy(records)
        updated = _find_record(updated_records, args.id)
        for raw in args.set:
            field, value = _parse_assignment(raw, "--set")
            _set_field(updated, field, value)
        validate_records(updated_records, repo_root=repo_root)
        if args.write:
            write_catalog_atomic(catalog_path, updated_records)
        print(_json_line(updated))
        return 0

    if args.command == "add-run":
        run_record = _read_run_file(Path(args.file))
        updated_records = copy.deepcopy(records)
        updated_records.append(run_record)
        validate_records(updated_records, repo_root=repo_root)
        if args.write:
            write_catalog_atomic(catalog_path, updated_records)
        print(_json_line(run_record))
        return 0

    raise CatalogError("unsupported command: {}".format(args.command))


def main(argv: Optional[Sequence[str]] = None, *, repo_root: Optional[Path] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    root = (repo_root or Path(__file__).resolve().parents[1]).resolve()
    try:
        return run(args, repo_root=root)
    except (CatalogError, OSError) as exc:
        print("error: {}".format(exc), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
