import copy
import contextlib
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
                    "TestRename": "path:hadoop-cephfs/src/test/java/TestRename.java"
                },
                "symbols": {
                    "ITestCephContractRename#testInherited": "url:https://example.test/AbstractContractRenameTest.java"
                },
            },
            "url": {"prefix": "url:"},
        },
        "external_reference_prefixes": ["case:", "java:", "url:"],
        "canonical_scopes": [
            {"records": "*", "projection": "path:docs/source.md"}
        ],
    }


def semantic_record():
    return {
        "kind": "semantic",
        "id": "SEM-RENAME-001",
        "axis": "rename",
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
    for index in range(1, 28):
        record = semantic_record()
        record["id"] = "SEM-RENAME-{:03d}".format(index)
        records.append(record)
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
        (self.root / "docs/source.md").write_text("# Source\n", encoding="utf-8")
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
            "  public void testSelfRename() {}\n"
            "  protected static void protectedHelper() {}\n"
            "  void helper() { calledOnly(); }\n"
            "  // public void commentedOnly() {}\n"
            "}\n",
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
        self.assertEqual(29, len(loaded))
        self.assertEqual("CATALOG", loaded[0]["id"])
        self.assertEqual("SEM-RENAME-027", loaded[-2]["id"])
        self.assertEqual("RUN-20260820-DOC-GATE", loaded[-1]["id"])

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
            {"records": "SEM-RENAME-*", "projection": "path:docs/source.md"}
        ]
        self.write_records([meta, *semantic_records(), run_record()])
        with self.assertRaisesRegex(
            docs_catalog.CatalogValidationError,
            "records outside canonical_scopes: RUN-20260820-DOC-GATE",
        ):
            docs_catalog.load_catalog(self.catalog, repo_root=self.root)

    def test_semantic_inventory_is_exact(self):
        records = semantic_records()[:-1]
        self.write_records([meta_record(), *records])
        with self.assertRaisesRegex(
            docs_catalog.CatalogValidationError,
            "semantic inventory must be SEM-RENAME-001..027: missing SEM-RENAME-027",
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
                self.write_records(self.records_with(record))
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
