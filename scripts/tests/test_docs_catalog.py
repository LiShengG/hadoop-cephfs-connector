import copy
import contextlib
import datetime as dt
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock


MODULE_PATH = Path(__file__).resolve().parents[1] / "docs-catalog.py"
SPEC = importlib.util.spec_from_file_location("docs_catalog", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
docs_catalog = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(docs_catalog)


def meta_record():
    return {
        "kind": "meta",
        "id": "CATALOG",
        "schema": "hadoop-cephfs-docs/v1",
        "historical_revision_unknown_runs": [],
        "basis_sources": {
            "HADOOP-SPEC": [
                "url:https://hadoop.apache.org/docs/r3.3.6/"
                "hadoop-project-dist/hadoop-common/filesystem/filesystem.html"
            ],
            "HDFS-3.3.6": [
                "url:https://github.com/apache/hadoop/tree/rel/release-3.3.6/"
                "hadoop-hdfs-project/hadoop-hdfs/src/main/java/org/apache/hadoop/hdfs"
            ],
            "PROJECT-ADR-0004": ["path:docs/adr/0004-example.md"],
        },
        "axis_basis_sources": {
            axis: {
                "HDFS-3.3.6": [
                    "url:https://github.com/apache/hadoop/blob/rel/release-3.3.6/"
                    "hadoop-hdfs-project/hadoop-hdfs/src/main/java/"
                    "org/apache/hadoop/hdfs/{}.java".format(axis)
                ]
            }
            for axis in docs_catalog.MIGRATED_SEMANTIC_COUNTS
        },
        "reference_resolvers": {
            "record": {"prefix": "record:", "route": "#/record/{id}"},
            "path": {"prefix": "path:", "base": "."},
            "case": {
                "prefix": "case:",
                "indexes": [
                    "path:docs/TEST-PLAN.md",
                    "path:docs/TEST-CASES-ECO.md",
                ],
            },
            "java": {
                "prefix": "java:",
                "classes": {
                    "TestRename": "path:hadoop-cephfs/src/test/java/TestRename.java",
                    "ITestCephContractRename": (
                        "path:hadoop-cephfs/src/test/java/ITestCephContractRename.java"
                    ),
                },
                "symbols": {
                    "ITestCephContractRename#testInherited": (
                        "url:https://github.com/apache/hadoop/blob/rel/release-3.3.6/"
                        "hadoop-common-project/hadoop-common/src/test/java/org/apache/"
                        "hadoop/fs/contract/AbstractContractRenameTest.java"
                    )
                },
            },
            "url": {"prefix": "url:"},
        },
        "external_reference_prefixes": ["case:", "java:", "url:"],
        "canonical_scopes": [
            {"records": "*", "projection": "path:docs/source.md"}
        ],
    }


def semantic_record(axis="rename", index=1):
    return {
        "kind": "semantic",
        "id": "SEM-{}-{:03d}".format(axis.upper(), index),
        "axis": axis,
        "api": "FileSystem#rename",
        "condition": "regular file src equals dst",
        "expected": True,
        "basis": ["HDFS-3.3.6"],
        "current": "Returns true as a no-op.",
        "classification": "MATCH",
        "guards": ["java:TestRename#testSelfRename"],
        "coverage": ["UNIT"],
        "tracking": [],
        "source_path": "docs/source.md",
    }


def semantic_records():
    records = []
    for axis, count in docs_catalog.MIGRATED_SEMANTIC_COUNTS.items():
        records.extend(semantic_record(axis, index) for index in range(1, count + 1))
    return records


def run_record(record_id="RUN-20260820-DOC-GATE"):
    return {
        "kind": "run",
        "id": record_id,
        "date": "2026-08-20",
        "title": "Documentation gate",
        "status": "PASS",
        "commands": ["bash scripts/check-docs.sh"],
        "result": "The gate passed.",
        "details": [],
        "integrity": [],
        "cleanup": [],
        "evidence": ["path:docs/source.md#result"],
        "commit": "abcdef0",
    }


class CatalogTestCase(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / "docs").mkdir()
        (self.root / "docs/source.md").write_text(
            "# Source\n\n## Result\n", encoding="utf-8"
        )
        (self.root / "docs/KNOWN-LIMITATIONS.md").write_text(
            "# Known limitations\n", encoding="utf-8"
        )
        (self.root / "docs/adr").mkdir()
        (self.root / "docs/adr/0004-example.md").write_text("# ADR\n", encoding="utf-8")
        (self.root / "docs/TEST-PLAN.md").write_text(
            "# Test plan\n\n## FN-18: rename semantics [P0]\n", encoding="utf-8"
        )
        (self.root / "docs/TEST-CASES-ECO.md").write_text(
            "# Ecosystem tests\n\n## SP-04: rename spike [P0]\n", encoding="utf-8"
        )
        (self.root / "docs/viewer").mkdir()
        (self.root / "docs/viewer/index.html").write_text(
            "<!doctype html><title>Viewer</title>\n", encoding="utf-8"
        )
        java_root = self.root / "hadoop-cephfs/src/test/java"
        java_root.mkdir(parents=True)
        (java_root / "TestRename.java").write_text(
            "class TestRename {\n"
            "  public void setUp() {}\n"
            "  public void testSelfRename() {}\n"
            "  protected static void protectedHelper() {}\n"
            "  void helper() { calledOnly(); }\n"
            "  // public void commentedOnly() {}\n"
            "}\n",
            encoding="utf-8",
        )
        (java_root / "ITestCephContractRename.java").write_text(
            "class ITestCephContractRename {}\n",
            encoding="utf-8",
        )
        for root_file in ("README.md", "PROGRESS.md", "EXPERIMENTS.md"):
            (self.root / root_file).write_text("# Test\n", encoding="utf-8")
        self.catalog = self.root / "docs/catalog.ndjson"
        self.records = [meta_record(), *semantic_records(), run_record()]
        self.write_records(self.records)

    def records_with(self, *extra_records):
        return [meta_record(), *semantic_records(), *extra_records]

    def write_records(self, records):
        text = "".join(
            json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n"
            for record in records
        )
        self.catalog.write_text(text, encoding="utf-8")

    def invoke(self, *arguments):
        stdout = io.StringIO()
        stderr = io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            result = docs_catalog.main(
                ["--catalog", str(self.catalog), *arguments], repo_root=self.root
            )
        return result, stdout.getvalue(), stderr.getvalue()


class ParserTests(CatalogTestCase):
    def test_parser_exposes_all_commands_and_repeatable_filters(self):
        parser = docs_catalog.build_parser()
        args = parser.parse_args(
            [
                "query",
                "--kind",
                "semantic",
                "--where",
                "classification=MATCH",
                "--where",
                "coverage=UNIT",
            ]
        )
        self.assertEqual("query", args.command)
        self.assertEqual(
            ["classification=MATCH", "coverage=UNIT"],
            args.where,
        )
        for command in ("validate", "show", "update", "add-run", "serve"):
            with self.subTest(command=command):
                self.assertIn(command, parser.format_help())


class ValidationTests(CatalogTestCase):
    def test_valid_catalog_loads(self):
        loaded = docs_catalog.load_catalog(self.catalog, repo_root=self.root)
        self.assertEqual(91, len(loaded))
        self.assertEqual("CATALOG", loaded[0]["id"])
        self.assertEqual("SEM-MKDIRS-010", loaded[-2]["id"])
        self.assertEqual("RUN-20260820-DOC-GATE", loaded[-1]["id"])

    def test_schema_v1_resolver_names_and_prefixes_are_fixed(self):
        meta = meta_record()
        meta["reference_resolvers"]["java"]["prefix"] = "j:"
        self.write_records([meta, *semantic_records()])
        with self.assertRaisesRegex(
            docs_catalog.CatalogValidationError,
            "resolver 'java' must use prefix 'java:'",
        ):
            docs_catalog.load_catalog(self.catalog, repo_root=self.root)

        meta = meta_record()
        meta["reference_resolvers"]["opaque-java"] = {"prefix": "opaque:"}
        self.write_records([meta, *semantic_records()])
        with self.assertRaisesRegex(
            docs_catalog.CatalogValidationError,
            "unsupported resolvers: opaque-java",
        ):
            docs_catalog.load_catalog(self.catalog, repo_root=self.root)

    def test_migrated_semantic_inventory_has_fixed_axis_ranges(self):
        self.assertEqual(
            {
                "rename": 27,
                "create": 21,
                "delete": 10,
                "sync": 16,
                "append": 5,
                "mkdirs": 10,
            },
            docs_catalog.MIGRATED_SEMANTIC_COUNTS,
        )
        for axis, count in docs_catalog.MIGRATED_SEMANTIC_COUNTS.items():
            with self.subTest(axis=axis):
                self.assertEqual(
                    {
                        "SEM-{}-{:03d}".format(axis.upper(), index)
                        for index in range(1, count + 1)
                    },
                    set(docs_catalog.EXPECTED_SEMANTIC_IDS_BY_AXIS[axis]),
                )

    def test_semantic_api_is_required(self):
        records = copy.deepcopy(self.records)
        del records[1]["api"]
        self.write_records(records)
        with self.assertRaisesRegex(
            docs_catalog.CatalogValidationError, "'api' must be a non-empty string"
        ):
            docs_catalog.load_catalog(self.catalog, repo_root=self.root)

    def test_readiness_accepts_accepted_risk(self):
        readiness = {
            "kind": "readiness_area",
            "id": "READY-ACCEPTED-RISK",
            "area": "Security",
            "status": "ACCEPTED_RISK",
            "required_evidence": "An approved risk record",
            "latest_report": [],
            "open_items": "Review at the next release gate",
        }
        self.write_records(self.records_with(readiness))
        loaded = docs_catalog.load_catalog(self.catalog, repo_root=self.root)
        self.assertEqual("ACCEPTED_RISK", loaded[-1]["status"])

    def test_revision_unknown_runs_require_explicit_historical_inventory(self):
        historical = run_record("RUN-20260816-HISTORICAL")
        del historical["commit"]
        historical["revision_unknown"] = True
        historical["commands"] = []
        records = self.records_with(historical)
        records[0]["historical_revision_unknown_runs"] = [historical["id"]]
        self.write_records(records)
        loaded = docs_catalog.load_catalog(self.catalog, repo_root=self.root)
        self.assertTrue(loaded[-1]["revision_unknown"])

        records[0]["historical_revision_unknown_runs"] = []
        self.write_records(records)
        with self.assertRaisesRegex(
            docs_catalog.CatalogValidationError,
            "historical revision_unknown run inventory mismatch",
        ):
            docs_catalog.load_catalog(self.catalog, repo_root=self.root)

        records[0]["historical_revision_unknown_runs"] = [
            historical["id"],
            "RUN-20260816-NOT-PRESENT",
        ]
        self.write_records(records)
        with self.assertRaisesRegex(
            docs_catalog.CatalogValidationError,
            "not revision_unknown runs RUN-20260816-NOT-PRESENT",
        ):
            docs_catalog.load_catalog(self.catalog, repo_root=self.root)

    def test_invalid_json_reports_physical_line(self):
        self.catalog.write_text(json.dumps(meta_record()) + "\n{" + "\n", encoding="utf-8")
        with self.assertRaisesRegex(docs_catalog.CatalogValidationError, "line 2: invalid JSON"):
            docs_catalog.load_catalog(self.catalog, repo_root=self.root)

    def test_duplicate_id_and_missing_meta_are_rejected(self):
        duplicate = semantic_record()
        duplicate["id"] = "SEM-RENAME-001"
        self.write_records([semantic_record(), duplicate])
        with self.assertRaises(docs_catalog.CatalogValidationError) as raised:
            docs_catalog.load_catalog(self.catalog, repo_root=self.root)
        message = str(raised.exception)
        self.assertIn("first record must have kind 'meta'", message)
        self.assertIn("duplicate id 'SEM-RENAME-001'", message)

    def test_different_semantic_requires_limitation_or_adr_path(self):
        record = semantic_record()
        record["classification"] = "DIFFERENT"
        record["tracking"] = []
        self.write_records([meta_record(), record])
        with self.assertRaisesRegex(
            docs_catalog.CatalogValidationError,
            "DIFFERENT semantics require a limitation or ADR path",
        ):
            docs_catalog.load_catalog(self.catalog, repo_root=self.root)

    def test_unknown_semantic_requires_none_coverage(self):
        record = semantic_record()
        record["classification"] = "UNKNOWN"
        record["coverage"] = ["UNIT"]
        self.write_records([meta_record(), record])
        with self.assertRaisesRegex(
            docs_catalog.CatalogValidationError, "UNKNOWN semantics require coverage \[NONE\]"
        ):
            docs_catalog.load_catalog(self.catalog, repo_root=self.root)

    def test_partial_guard_and_role_must_resolve_to_the_same_guard(self):
        records = copy.deepcopy(self.records)
        records[1]["partial_guards"] = ["case:FN-18"]
        records[1]["guard_roles"] = {"FN-18": "planned, not executable"}
        self.write_records(records)
        loaded = docs_catalog.load_catalog(self.catalog, repo_root=self.root)
        self.assertEqual(["case:FN-18"], loaded[1]["partial_guards"])

        records[1]["guard_roles"] = {"FN-17": "not one of this record's guards"}
        self.write_records(records)
        with self.assertRaisesRegex(
            docs_catalog.CatalogValidationError,
            "guard_roles key has no matching guard: FN-17",
        ):
            docs_catalog.load_catalog(self.catalog, repo_root=self.root)

    def test_partial_guards_are_distinct_resolvable_references(self):
        records = copy.deepcopy(self.records)
        records[1]["partial_guards"] = ["java:TestRename#testSelfRename"]
        self.write_records(records)
        with self.assertRaisesRegex(
            docs_catalog.CatalogValidationError,
            "guards and partial_guards overlap",
        ):
            docs_catalog.load_catalog(self.catalog, repo_root=self.root)

        records[1]["partial_guards"] = ["not-a-reference"]
        self.write_records(records)
        with self.assertRaisesRegex(
            docs_catalog.CatalogValidationError,
            "unresolved or unapproved reference",
        ):
            docs_catalog.load_catalog(self.catalog, repo_root=self.root)

    def test_project_basis_requires_corresponding_adr_tracking(self):
        record = semantic_record()
        record["basis"] = ["PROJECT-ADR-0004"]
        record["tracking"] = ["path:docs/KNOWN-LIMITATIONS.md#lim-010-example"]
        self.write_records([meta_record(), record])
        with self.assertRaisesRegex(
            docs_catalog.CatalogValidationError,
            "basis PROJECT-ADR-0004 requires the corresponding ADR path",
        ):
            docs_catalog.load_catalog(self.catalog, repo_root=self.root)

    def test_semantics_require_resolvable_global_basis_sources(self):
        meta = meta_record()
        meta.pop("basis_sources")
        self.write_records([meta, *semantic_records()])
        with self.assertRaisesRegex(
            docs_catalog.CatalogValidationError,
            "semantic records require 'basis_sources'",
        ):
            docs_catalog.load_catalog(self.catalog, repo_root=self.root)

        invalid_sources = [
            ("bare-source", "must be an official Hadoop 3.3.6 URL"),
            ("record:SEM-RENAME-001", "must be an official Hadoop 3.3.6 URL"),
            ("path:docs/missing.md", "must be an official Hadoop 3.3.6 URL"),
            ("url:not-an-absolute-url", "must be an official Hadoop 3.3.6 URL"),
        ]
        for source, expected_error in invalid_sources:
            with self.subTest(source=source):
                meta = meta_record()
                meta["basis_sources"]["HDFS-3.3.6"] = [source]
                meta.pop("axis_basis_sources")
                self.write_records([meta, *semantic_records()])
                with self.assertRaisesRegex(
                    docs_catalog.CatalogValidationError, expected_error
                ):
                    docs_catalog.load_catalog(self.catalog, repo_root=self.root)

    def test_axis_basis_sources_are_validated_and_preferred(self):
        meta = meta_record()
        meta.pop("axis_basis_sources")
        self.write_records([meta, *semantic_records()])
        docs_catalog.load_catalog(self.catalog, repo_root=self.root)

        meta = meta_record()
        del meta["basis_sources"]["HDFS-3.3.6"]
        self.write_records([meta, *semantic_records()])
        docs_catalog.load_catalog(self.catalog, repo_root=self.root)

        invalid_axis_sources = [
            ("bare-source", "must be an official Hadoop 3.3.6 URL"),
            ("path:docs/missing.md", "must be an official Hadoop 3.3.6 URL"),
            ("url:not-an-absolute-url", "must be an official Hadoop 3.3.6 URL"),
        ]
        for source, expected_error in invalid_axis_sources:
            with self.subTest(source=source):
                meta = meta_record()
                meta["axis_basis_sources"]["create"]["HDFS-3.3.6"] = [source]
                self.write_records([meta, *semantic_records()])
                with self.assertRaisesRegex(
                    docs_catalog.CatalogValidationError, expected_error
                ):
                    docs_catalog.load_catalog(self.catalog, repo_root=self.root)

        meta = meta_record()
        del meta["axis_basis_sources"]["create"]["HDFS-3.3.6"]
        self.write_records([meta, *semantic_records()])
        with self.assertRaisesRegex(
            docs_catalog.CatalogValidationError,
            "basis 'HDFS-3.3.6' has no entry in meta.axis_basis_sources.create",
        ):
            docs_catalog.load_catalog(self.catalog, repo_root=self.root)

    def test_axis_basis_sources_reject_invalid_shapes_and_axes(self):
        invalid_values = [None, [], {}]
        for value in invalid_values:
            with self.subTest(value=value):
                meta = meta_record()
                meta["axis_basis_sources"] = value
                self.write_records([meta, *semantic_records()])
                with self.assertRaisesRegex(
                    docs_catalog.CatalogValidationError,
                    "'axis_basis_sources' must be a non-empty object",
                ):
                    docs_catalog.load_catalog(self.catalog, repo_root=self.root)

        meta = meta_record()
        meta["axis_basis_sources"]["visibility"] = {
            "HDFS-3.3.6": ["url:https://example.test/visibility.java"]
        }
        self.write_records([meta, *semantic_records()])
        with self.assertRaisesRegex(
            docs_catalog.CatalogValidationError,
            "axis_basis_sources.visibility: axis is not in the migrated semantic inventory",
        ):
            docs_catalog.load_catalog(self.catalog, repo_root=self.root)

        meta = meta_record()
        meta["axis_basis_sources"]["create"] = []
        self.write_records([meta, *semantic_records()])
        with self.assertRaisesRegex(
            docs_catalog.CatalogValidationError,
            "axis_basis_sources.create must be a non-empty object",
        ):
            docs_catalog.load_catalog(self.catalog, repo_root=self.root)

    def test_basis_sources_reject_wrong_hosts_releases_and_adr_paths(self):
        cases = [
            (
                "HADOOP-SPEC",
                "url:https://example.test/docs/r3.3.6/filesystem.html",
                "must be an official Hadoop 3.3.6 URL",
            ),
            (
                "HDFS-3.3.6",
                "url:https://github.com/apache/hadoop/blob/rel/release-3.4.0/"
                "hadoop-hdfs-project/hadoop-hdfs/src/main/java/HdfsSource.java",
                "must be an official Hadoop 3.3.6 URL",
            ),
            (
                "HDFS-3.3.6",
                "url:https://github.com/apache/hadoop/blob/rel/release-3.3.6/"
                "%2e%2e/main/HdfsSource.java",
                "must be an official Hadoop 3.3.6 URL",
            ),
            (
                "PROJECT-ADR-0004",
                "path:docs/adr/0005-wrong.md",
                r"must be the corresponding docs/adr/0004-\*.md path",
            ),
        ]
        (self.root / "docs/adr/0005-wrong.md").write_text(
            "# Wrong ADR\n", encoding="utf-8"
        )
        for basis_name, source, expected_error in cases:
            with self.subTest(basis=basis_name, source=source):
                meta = meta_record()
                meta["basis_sources"][basis_name] = [source]
                self.write_records([meta, *semantic_records()])
                with self.assertRaisesRegex(
                    docs_catalog.CatalogValidationError, expected_error
                ):
                    docs_catalog.load_catalog(self.catalog, repo_root=self.root)

    def test_axis_basis_source_keys_equal_used_non_project_basis(self):
        meta = meta_record()
        del meta["axis_basis_sources"]["create"]["HDFS-3.3.6"]
        self.write_records([meta, *semantic_records()])
        with self.assertRaisesRegex(
            docs_catalog.CatalogValidationError,
            "axis_basis_sources.create keys must equal the used non-project Basis set: "
            "missing HDFS-3.3.6",
        ):
            docs_catalog.load_catalog(self.catalog, repo_root=self.root)

        meta = meta_record()
        meta["axis_basis_sources"]["sync"]["HADOOP-SPEC"] = [
            "url:https://hadoop.apache.org/docs/r3.3.6/"
            "hadoop-project-dist/hadoop-common/filesystem/filesystem.html"
        ]
        self.write_records([meta, *semantic_records()])
        with self.assertRaisesRegex(
            docs_catalog.CatalogValidationError,
            "axis_basis_sources.sync keys must equal the used non-project Basis set: "
            "unused HADOOP-SPEC",
        ):
            docs_catalog.load_catalog(self.catalog, repo_root=self.root)

    def test_project_basis_can_fall_back_to_global_source(self):
        records = semantic_records()
        records[0]["basis"] = ["PROJECT-ADR-0004"]
        records[0]["tracking"] = ["path:docs/adr/0004-example.md"]
        self.write_records([meta_record(), *records])
        docs_catalog.load_catalog(self.catalog, repo_root=self.root)

    def test_each_coverage_type_requires_its_specific_guard(self):
        cases = [
            ("UNIT", ["java:Helper#method"], "UNIT coverage requires"),
            (
                "CONTRACT",
                ["java:ITestCephFileSystemMeta#method"],
                "CONTRACT coverage requires",
            ),
            ("CLUSTER", ["java:TestCephFileSystemMeta#method"], "CLUSTER coverage requires"),
            ("SPIKE", ["case:FN-18"], "SPIKE coverage requires"),
        ]
        for coverage, guards, expected_error in cases:
            with self.subTest(coverage=coverage):
                record = semantic_record()
                record["coverage"] = [coverage]
                record["guards"] = guards
                self.write_records([meta_record(), record])
                with self.assertRaisesRegex(
                    docs_catalog.CatalogValidationError, expected_error
                ):
                    docs_catalog.load_catalog(self.catalog, repo_root=self.root)

    def test_automated_coverage_requires_exact_java_method(self):
        record = semantic_record()
        record["guards"] = ["case:FN-18"]
        self.write_records([meta_record(), record])
        with self.assertRaisesRegex(
            docs_catalog.CatalogValidationError,
            "automated coverage requires a java:Class#method guard",
        ):
            docs_catalog.load_catalog(self.catalog, repo_root=self.root)

    def test_semantic_java_guards_must_name_test_methods(self):
        for field in ("guards", "partial_guards"):
            with self.subTest(field=field):
                records = copy.deepcopy(self.records)
                records[1][field] = ["java:TestRename#setUp"]
                self.write_records(records)
                with self.assertRaisesRegex(
                    docs_catalog.CatalogValidationError,
                    "semantic Java guard method must start with 'test'",
                ):
                    docs_catalog.load_catalog(self.catalog, repo_root=self.root)

    def test_none_coverage_cannot_be_combined(self):
        record = semantic_record()
        record["coverage"] = ["NONE", "UNIT"]
        self.write_records([meta_record(), record])
        with self.assertRaisesRegex(
            docs_catalog.CatalogValidationError, "coverage NONE cannot be combined"
        ):
            docs_catalog.load_catalog(self.catalog, repo_root=self.root)

    def test_path_traversal_and_unresolved_records_are_rejected(self):
        record = semantic_record()
        record["source_path"] = "%2e%2e/outside.md"
        record["tracking"] = ["record:DOES-NOT-EXIST"]
        self.write_records([meta_record(), record])
        with self.assertRaises(docs_catalog.CatalogValidationError) as raised:
            docs_catalog.load_catalog(self.catalog, repo_root=self.root)
        message = str(raised.exception)
        self.assertIn("must not contain '..'", message)
        self.assertIn("unresolved record reference", message)

    def test_markdown_path_fragments_must_name_real_headings(self):
        (self.root / "docs/source.md").write_text(
            "# Source\n\n## Result\n\n```text\n## not-a-heading\n```\n",
            encoding="utf-8",
        )
        records = copy.deepcopy(self.records)
        records[-1]["evidence"] = ["path:docs/source.md#result"]
        self.write_records(records)
        docs_catalog.load_catalog(self.catalog, repo_root=self.root)

        records[-1]["evidence"] = ["path:docs/source.md#not-a-heading"]
        self.write_records(records)
        with self.assertRaisesRegex(
            docs_catalog.CatalogValidationError,
            "Markdown heading fragment does not exist: #not-a-heading",
        ):
            docs_catalog.load_catalog(self.catalog, repo_root=self.root)

    def test_bare_explicit_reference_must_resolve(self):
        record = semantic_record()
        record["source_refs"] = ["SEM-DOES-NOT-EXIST"]
        self.write_records([meta_record(), record])
        with self.assertRaisesRegex(
            docs_catalog.CatalogValidationError, "unresolved or unapproved reference"
        ):
            docs_catalog.load_catalog(self.catalog, repo_root=self.root)

    def test_structured_record_kinds_require_their_schema_fields(self):
        records_and_missing_fields = [
            (
                {
                    "kind": "project",
                    "id": "PROJECT-STATUS",
                    "updated": "2026-08-20",
                    "commit": "abcdef0",
                    "active_milestone": "catalog experiment",
                },
                "active_milestone",
            ),
            (
                {
                    "kind": "baseline",
                    "id": "BASELINE-E1",
                    "environment": "E1",
                    "commit": "abcdef0",
                    "result": "pass",
                    "report": "path:docs/source.md",
                },
                "report",
            ),
            (
                {
                    "kind": "risk",
                    "id": "RISK-ONE",
                    "name": "Risk",
                    "impact": "Impact",
                    "required_evidence": ["case:FN-18"],
                    "tracking": ["path:docs/KNOWN-LIMITATIONS.md"],
                },
                "required_evidence",
            ),
            (
                {
                    "kind": "evidence_request",
                    "id": "EVIDENCE-ONE",
                    "checkpoint": "T08-CHECK",
                    "owner": "T08",
                    "required_execution": "Run the check",
                    "result_needed": "A dated result",
                    "destination": "A new report",
                },
                "destination",
            ),
            (
                {
                    "kind": "constraint",
                    "id": "CONSTRAINT-ONE",
                    "name": "Constraint",
                    "required_handling": "Follow the constraint",
                    "source": "path:docs/source.md",
                },
                "source",
            ),
            (
                {
                    "kind": "milestone",
                    "id": "MILESTONE-ONE",
                    "milestone": "Beta",
                    "requirement": "All beta gates",
                    "tracking": ["record:SEM-RENAME-001"],
                },
                "tracking",
            ),
        ]
        for record, missing_field in records_and_missing_fields:
            with self.subTest(kind=record["kind"]):
                self.write_records(self.records_with(record))
                docs_catalog.load_catalog(self.catalog, repo_root=self.root)
                invalid = copy.deepcopy(record)
                invalid.pop(missing_field)
                self.write_records(self.records_with(invalid))
                with self.assertRaisesRegex(
                    docs_catalog.CatalogValidationError,
                    "'{}' must".format(missing_field),
                ):
                    docs_catalog.load_catalog(self.catalog, repo_root=self.root)

    def test_reference_array_fields_reject_scalar_values(self):
        risk = {
            "kind": "risk",
            "id": "RISK-ONE",
            "name": "Risk",
            "impact": "Impact",
            "required_evidence": "case:FN-18",
            "tracking": ["path:docs/KNOWN-LIMITATIONS.md"],
        }
        self.write_records(self.records_with(risk))
        with self.assertRaisesRegex(
            docs_catalog.CatalogValidationError,
            "'required_evidence' must be an array",
        ):
            docs_catalog.load_catalog(self.catalog, repo_root=self.root)

    def test_work_requires_tracking_and_category_specific_fields(self):
        valid_records = [
            {
                "kind": "work",
                "id": "T08",
                "category": "active",
                "state": "IN_PROGRESS",
                "current_finding": "A finding",
                "next_action": "An action",
                "exit_criteria": "A criterion",
                "tracking": ["path:docs/source.md"],
            },
            {
                "kind": "work",
                "id": "T09",
                "category": "blocker",
                "state": "BLOCKED",
                "blocker": "A blocker",
                "resolution_condition": "A resolution condition",
                "tracking": ["path:docs/source.md"],
            },
        ]
        for record in valid_records:
            with self.subTest(category=record["category"]):
                self.write_records(self.records_with(record))
                docs_catalog.load_catalog(self.catalog, repo_root=self.root)

        missing_fields = [
            (valid_records[0], "current_finding"),
            (valid_records[0], "next_action"),
            (valid_records[0], "exit_criteria"),
            (valid_records[1], "blocker"),
            (valid_records[1], "resolution_condition"),
        ]
        for original, missing_field in missing_fields:
            with self.subTest(category=original["category"], field=missing_field):
                invalid = copy.deepcopy(original)
                invalid.pop(missing_field)
                self.write_records(self.records_with(invalid))
                with self.assertRaisesRegex(
                    docs_catalog.CatalogValidationError,
                    "'{}' must be a non-empty string".format(missing_field),
                ):
                    docs_catalog.load_catalog(self.catalog, repo_root=self.root)

        invalid = copy.deepcopy(valid_records[0])
        invalid["tracking"] = []
        self.write_records(self.records_with(invalid))
        with self.assertRaisesRegex(
            docs_catalog.CatalogValidationError, "'tracking' must not be empty"
        ):
            docs_catalog.load_catalog(self.catalog, repo_root=self.root)

    def test_readiness_area_requires_complete_typed_fields(self):
        valid = {
            "kind": "readiness_area",
            "id": "READY-CORRECTNESS",
            "area": "Correctness",
            "status": "PARTIAL",
            "required_evidence": "Applicable test definitions",
            "latest_report": [],
            "open_items": "More cases remain",
        }
        self.write_records(self.records_with(valid))
        docs_catalog.load_catalog(self.catalog, repo_root=self.root)

        for missing_field in ("area", "required_evidence", "open_items"):
            with self.subTest(field=missing_field):
                invalid = copy.deepcopy(valid)
                invalid.pop(missing_field)
                self.write_records(self.records_with(invalid))
                with self.assertRaisesRegex(
                    docs_catalog.CatalogValidationError,
                    "'{}' must be a non-empty string".format(missing_field),
                ):
                    docs_catalog.load_catalog(self.catalog, repo_root=self.root)

        invalid = copy.deepcopy(valid)
        invalid["latest_report"] = "path:docs/source.md"
        self.write_records(self.records_with(invalid))
        with self.assertRaisesRegex(
            docs_catalog.CatalogValidationError,
            "'latest_report' must be an array of non-empty strings",
        ):
            docs_catalog.load_catalog(self.catalog, repo_root=self.root)

    def test_project_and_baseline_commits_are_sha_like(self):
        records = [
            {
                "kind": "project",
                "id": "PROJECT-STATUS",
                "updated": "2026-08-20",
                "commit": "not-a-sha",
                "active_milestone": "catalog experiment",
            },
            {
                "kind": "baseline",
                "id": "BASELINE-E1",
                "environment": "E1",
                "commit": "not-a-sha",
                "result": "pass",
                "report": "path:docs/source.md",
            },
        ]
        for record in records:
            with self.subTest(kind=record["kind"]):
                self.write_records(self.records_with(record))
                with self.assertRaisesRegex(
                    docs_catalog.CatalogValidationError, "'commit' must be a SHA-like string"
                ):
                    docs_catalog.load_catalog(self.catalog, repo_root=self.root)

    def test_canonical_scope_selectors_must_match_records(self):
        meta = meta_record()
        meta["canonical_scopes"] = [
            {
                "records": "SEM-RENAME-*,NO-SUCH-RECORD-*",
                "projection": "path:docs/source.md",
            },
            {"records": "RUN-*", "projection": "path:docs/source.md"},
        ]
        self.write_records([meta, *semantic_records(), run_record()])
        with self.assertRaisesRegex(
            docs_catalog.CatalogValidationError,
            "selector matches no records: NO-SUCH-RECORD-",
        ):
            docs_catalog.load_catalog(self.catalog, repo_root=self.root)

    def test_every_record_must_be_covered_by_a_canonical_scope(self):
        meta = meta_record()
        meta["canonical_scopes"] = [
            {"records": "SEM-*", "projection": "path:docs/source.md"}
        ]
        self.write_records([meta, *semantic_records(), run_record()])
        with self.assertRaisesRegex(
            docs_catalog.CatalogValidationError,
            "records outside canonical_scopes: RUN-20260820-DOC-GATE",
        ):
            docs_catalog.load_catalog(self.catalog, repo_root=self.root)

    def test_record_must_not_match_multiple_canonical_scopes(self):
        meta = meta_record()
        meta["canonical_scopes"] = [
            {"records": "*", "projection": "path:docs/source.md"},
            {"records": "SEM-*", "projection": "path:docs/source.md"},
        ]
        self.write_records([meta, *semantic_records(), run_record()])
        with self.assertRaisesRegex(
            docs_catalog.CatalogValidationError,
            "records matched by multiple canonical_scopes: SEM-APPEND-001",
        ):
            docs_catalog.load_catalog(self.catalog, repo_root=self.root)

    def test_semantic_inventory_is_exact(self):
        records = semantic_records()[:-1]
        self.write_records([meta_record(), *records])
        with self.assertRaisesRegex(
            docs_catalog.CatalogValidationError,
            "semantic inventory for 'mkdirs' must be SEM-MKDIRS-001..010: "
            "missing SEM-MKDIRS-010",
        ):
            docs_catalog.load_catalog(self.catalog, repo_root=self.root)

    def test_semantic_id_must_match_axis_even_when_global_ids_are_unchanged(self):
        records = semantic_records()
        create = next(record for record in records if record["id"] == "SEM-CREATE-001")
        delete = next(record for record in records if record["id"] == "SEM-DELETE-001")
        create["axis"], delete["axis"] = delete["axis"], create["axis"]
        self.write_records([meta_record(), *records])
        with self.assertRaisesRegex(
            docs_catalog.CatalogValidationError,
            "semantic id 'SEM-CREATE-001' does not match axis 'delete'",
        ):
            docs_catalog.load_catalog(self.catalog, repo_root=self.root)

    def test_unmigrated_semantic_axis_is_rejected(self):
        records = semantic_records()
        records[0]["axis"] = "visibility"
        self.write_records([meta_record(), *records])
        with self.assertRaisesRegex(
            docs_catalog.CatalogValidationError,
            "semantic axis is not migrated: visibility",
        ):
            docs_catalog.load_catalog(self.catalog, repo_root=self.root)

    def test_unknown_case_reference_is_rejected(self):
        records = semantic_records()
        records[0]["tracking"] = ["case:FN-999"]
        self.write_records([meta_record(), *records])
        with self.assertRaisesRegex(
            docs_catalog.CatalogValidationError, "unknown case reference: case:FN-999"
        ):
            docs_catalog.load_catalog(self.catalog, repo_root=self.root)

    def test_known_local_and_inherited_java_symbols_are_accepted(self):
        records = semantic_records()
        records[0]["coverage"] = ["CONTRACT"]
        records[0]["guards"] = [
            "java:ITestCephContractRename#testInherited",
            "case:FN-18",
        ]
        records[0]["tracking"] = ["java:TestRename#protectedHelper"]
        self.write_records([meta_record(), *records])
        docs_catalog.load_catalog(self.catalog, repo_root=self.root)

    def test_unknown_java_class_and_method_are_rejected(self):
        cases = [
            ("java:MissingClass#testSelfRename", "unknown Java class"),
            ("java:TestRename#missingMethod", "unknown Java method"),
            ("java:TestRename#calledOnly", "unknown Java method"),
            ("java:TestRename#commentedOnly", "unknown Java method"),
        ]
        for guard, expected_error in cases:
            with self.subTest(guard=guard):
                records = semantic_records()
                records[0]["tracking"] = [guard]
                self.write_records([meta_record(), *records])
                with self.assertRaisesRegex(
                    docs_catalog.CatalogValidationError, expected_error
                ):
                    docs_catalog.load_catalog(self.catalog, repo_root=self.root)

    def test_java_class_mapping_key_must_be_a_class_name(self):
        meta = meta_record()
        classes = meta["reference_resolvers"]["java"]["classes"]
        classes["Bad/Class"] = classes.pop("TestRename")
        self.write_records([meta, *semantic_records()])
        with self.assertRaisesRegex(
            docs_catalog.CatalogValidationError, "invalid Java class key"
        ):
            docs_catalog.load_catalog(self.catalog, repo_root=self.root)

    def test_java_class_mapping_key_must_match_source_stem(self):
        meta = meta_record()
        classes = meta["reference_resolvers"]["java"]["classes"]
        classes["DifferentTestName"] = classes.pop("TestRename")
        self.write_records([meta, *semantic_records()])
        with self.assertRaisesRegex(
            docs_catalog.CatalogValidationError,
            "Java class key must match source stem 'TestRename'",
        ):
            docs_catalog.load_catalog(self.catalog, repo_root=self.root)

    def test_inherited_java_symbol_requires_local_class_mapping(self):
        meta = meta_record()
        del meta["reference_resolvers"]["java"]["classes"][
            "ITestCephContractRename"
        ]
        self.write_records([meta, *semantic_records()])
        with self.assertRaisesRegex(
            docs_catalog.CatalogValidationError,
            "inherited Java symbol class has no valid local class mapping",
        ):
            docs_catalog.load_catalog(self.catalog, repo_root=self.root)

    def test_inherited_java_symbol_requires_official_hadoop_test_source(self):
        invalid_targets = [
            "url:https://example.test/AbstractContractRenameTest.java",
            (
                "url:https://github.com/apache/hadoop/blob/rel/release-3.3.6/"
                "hadoop-common-project/hadoop-common/src/main/java/org/apache/hadoop/"
                "fs/contract/AbstractContractRenameTest.java"
            ),
            "path:README.md",
        ]
        for target in invalid_targets:
            with self.subTest(target=target):
                meta = meta_record()
                meta["reference_resolvers"]["java"]["symbols"][
                    "ITestCephContractRename#testInherited"
                ] = target
                self.write_records([meta, *semantic_records()])
                with self.assertRaisesRegex(
                    docs_catalog.CatalogValidationError,
                    "inherited symbol target must be an official Hadoop 3.3.6 test Java URL",
                ):
                    docs_catalog.load_catalog(self.catalog, repo_root=self.root)

    def test_record_ids_reject_spaces_and_slashes(self):
        for invalid_id in ("BAD ID", "BAD/ID", "lowercase"):
            with self.subTest(record_id=invalid_id):
                record = run_record(invalid_id)
                self.write_records(self.records_with(record))
                with self.assertRaisesRegex(
                    docs_catalog.CatalogValidationError, "invalid id format"
                ):
                    docs_catalog.load_catalog(self.catalog, repo_root=self.root)

    def test_run_revision_descriptor_is_exclusive_and_typed(self):
        valid_descriptors = [
            {"commit": "abcdef0"},
            {"worktree_base": "abcdef0", "dirty": True},
            {"revision_unknown": True},
        ]
        for descriptor in valid_descriptors:
            with self.subTest(descriptor=descriptor):
                record = run_record()
                record.pop("commit")
                record.update(descriptor)
                records = self.records_with(record)
                if descriptor.get("revision_unknown") is True:
                    records[0]["historical_revision_unknown_runs"] = [record["id"]]
                self.write_records(records)
                docs_catalog.load_catalog(self.catalog, repo_root=self.root)

        invalid = run_record()
        invalid["revision_unknown"] = True
        self.write_records(self.records_with(invalid))
        with self.assertRaisesRegex(
            docs_catalog.CatalogValidationError, "exactly one revision descriptor"
        ):
            docs_catalog.load_catalog(self.catalog, repo_root=self.root)

        invalid = run_record()
        invalid.pop("commit")
        invalid.update({"worktree_base": "not-a-sha", "dirty": False})
        self.write_records(self.records_with(invalid))
        with self.assertRaises(docs_catalog.CatalogValidationError) as raised:
            docs_catalog.load_catalog(self.catalog, repo_root=self.root)
        self.assertIn("'worktree_base' must be a SHA-like string", str(raised.exception))
        self.assertIn("dirty:true", str(raised.exception))

    def test_new_runs_require_commands_but_migrated_history_may_omit_them(self):
        record = run_record()
        record["commands"] = []
        self.write_records(self.records_with(record))
        with self.assertRaisesRegex(
            docs_catalog.CatalogValidationError, "'commands' must not be empty"
        ):
            docs_catalog.load_catalog(self.catalog, repo_root=self.root)

        record.pop("commit")
        record["revision_unknown"] = True
        records = self.records_with(record)
        records[0]["historical_revision_unknown_runs"] = [record["id"]]
        self.write_records(records)
        docs_catalog.load_catalog(self.catalog, repo_root=self.root)

    def test_run_date_must_not_be_in_the_future(self):
        record = run_record()
        record["date"] = (dt.date.today() + dt.timedelta(days=1)).isoformat()
        self.write_records(self.records_with(record))
        with self.assertRaisesRegex(
            docs_catalog.CatalogValidationError, "run date must not be in the future"
        ):
            docs_catalog.load_catalog(self.catalog, repo_root=self.root)


class QueryAndShowTests(CatalogTestCase):
    def test_query_filters_scalar_and_array_fields(self):
        result, stdout, stderr = self.invoke(
            "query",
            "--kind",
            "semantic",
            "--where",
            "classification=MATCH",
            "--where",
            "coverage=UNIT",
            "--where",
            "id=SEM-RENAME-001",
        )
        self.assertEqual(0, result, stderr)
        lines = stdout.splitlines()
        self.assertEqual(1, len(lines))
        self.assertEqual("SEM-RENAME-001", json.loads(lines[0])["id"])

    def test_show_prints_only_the_requested_record(self):
        result, stdout, stderr = self.invoke("show", "RUN-20260820-DOC-GATE")
        self.assertEqual(0, result, stderr)
        self.assertEqual("RUN-20260820-DOC-GATE", json.loads(stdout)["id"])
        self.assertNotIn("SEM-RENAME-001", stdout)


class UpdateTests(CatalogTestCase):
    def test_update_is_dry_run_by_default(self):
        before = self.catalog.read_bytes()
        result, stdout, stderr = self.invoke(
            "update", "RUN-20260820-DOC-GATE", "--set", "status=BLOCKED"
        )
        self.assertEqual(0, result, stderr)
        self.assertEqual("BLOCKED", json.loads(stdout)["status"])
        self.assertEqual(before, self.catalog.read_bytes())

    def test_update_write_atomically_persists_valid_record(self):
        before_lines = self.catalog.read_bytes().splitlines(keepends=True)
        result, stdout, stderr = self.invoke(
            "update",
            "RUN-20260820-DOC-GATE",
            "--set",
            'details=["rerun required"]',
            "--write",
        )
        self.assertEqual(0, result, stderr)
        self.assertEqual(["rerun required"], json.loads(stdout)["details"])
        loaded = docs_catalog.load_catalog(self.catalog, repo_root=self.root)
        self.assertEqual(
            ["rerun required"],
            next(r for r in loaded if r["id"] == "RUN-20260820-DOC-GATE")["details"],
        )
        after_lines = self.catalog.read_bytes().splitlines(keepends=True)
        self.assertEqual(before_lines[:-1], after_lines[:-1])
        self.assertNotEqual(before_lines[-1], after_lines[-1])
        self.assertTrue(
            self.catalog.read_text(encoding="utf-8").startswith('{"kind":"meta","id":"CATALOG"')
        )
        self.assertEqual([], list(self.catalog.parent.glob(".catalog.ndjson-*.tmp")))


class AddRunTests(CatalogTestCase):
    def test_add_run_dry_run_and_write(self):
        new_run = run_record("RUN-20260821-DOC-GATE")
        new_run_file = self.root / "new-run.json"
        new_run_file.write_text(json.dumps(new_run), encoding="utf-8")
        before = self.catalog.read_bytes()

        result, stdout, stderr = self.invoke("add-run", str(new_run_file))
        self.assertEqual(0, result, stderr)
        self.assertEqual("RUN-20260821-DOC-GATE", json.loads(stdout)["id"])
        self.assertEqual(before, self.catalog.read_bytes())

        result, stdout, stderr = self.invoke("add-run", str(new_run_file), "--write")
        self.assertEqual(0, result, stderr)
        loaded = docs_catalog.load_catalog(self.catalog, repo_root=self.root)
        self.assertIn("RUN-20260821-DOC-GATE", [record["id"] for record in loaded])


class ServeTests(CatalogTestCase):
    def handler(self, path):
        handler = object.__new__(docs_catalog.CatalogRequestHandler)
        handler.repo_root = self.root.resolve()
        handler.path = path
        handler.send_error = mock.MagicMock()
        return handler

    def test_serve_prints_direct_viewer_url(self):
        server = mock.MagicMock()
        server.__enter__.return_value = server
        server.server_address = ("127.0.0.1", 43123)
        server.serve_forever.side_effect = KeyboardInterrupt
        stdout = io.StringIO()
        with mock.patch.object(
            docs_catalog.http.server, "ThreadingHTTPServer", return_value=server
        ), contextlib.redirect_stdout(stdout):
            docs_catalog._serve(self.root, "127.0.0.1", 0)
        self.assertIn("http://127.0.0.1:43123/docs/viewer/", stdout.getvalue())

    def test_allowlist_accepts_only_documentation_and_test_source_paths(self):
        allowed = [
            "/docs/source.md",
            "/docs/viewer/",
            "/hadoop-cephfs/src/test/java/TestRename.java",
            "/README.md",
            "/PROGRESS.md",
            "/EXPERIMENTS.md",
        ]
        blocked = [
            "/",
            "/.git/config",
            "/pom.xml",
            "/AGENTS.md",
            "/docs/.hidden",
            "/docs/%2e%2e/README.md",
            "/docs/%252e%252e/README.md",
            "/hadoop-cephfs/src/main/java/Secret.java",
            "/hadoop-cephfs/src/test/java/../../../../pom.xml",
        ]
        for path in allowed:
            with self.subTest(path=path):
                self.assertTrue(self.handler(path)._is_allowed_request())
        for path in blocked:
            with self.subTest(path=path):
                self.assertFalse(self.handler(path)._is_allowed_request())

    def test_get_and_head_both_reject_disallowed_paths(self):
        for method_name in ("do_GET", "do_HEAD"):
            with self.subTest(method=method_name):
                handler = self.handler("/.git/config")
                with mock.patch.object(
                    docs_catalog.http.server.SimpleHTTPRequestHandler, method_name
                ) as delegated:
                    getattr(handler, method_name)()
                handler.send_error.assert_called_once_with(
                    403, "Path is outside the documentation allowlist"
                )
                delegated.assert_not_called()

    def test_get_and_head_delegate_allowed_paths(self):
        for method_name in ("do_GET", "do_HEAD"):
            with self.subTest(method=method_name):
                handler = self.handler("/docs/source.md")
                with mock.patch.object(
                    docs_catalog.http.server.SimpleHTTPRequestHandler, method_name
                ) as delegated:
                    getattr(handler, method_name)()
                handler.send_error.assert_not_called()
                delegated.assert_called_once()

    def test_symlink_escape_is_rejected(self):
        private = self.root / "private.txt"
        private.write_text("private\n", encoding="utf-8")
        link = self.root / "docs/escape.txt"
        try:
            link.symlink_to(private)
        except OSError as exc:
            self.skipTest("symlinks unavailable: {}".format(exc))
        self.assertFalse(self.handler("/docs/escape.txt")._is_allowed_request())


if __name__ == "__main__":
    unittest.main()
