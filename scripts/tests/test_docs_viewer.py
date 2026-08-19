from html.parser import HTMLParser
from pathlib import Path
import re
import unittest


REPO_ROOT = Path(__file__).resolve().parents[2]
VIEWER_ROOT = REPO_ROOT / "docs/viewer"


class AssetParser(HTMLParser):
    def __init__(self):
        super().__init__()
        self.stylesheets = []
        self.scripts = []

    def handle_starttag(self, tag, attrs):
        attributes = dict(attrs)
        if tag == "link" and attributes.get("rel") == "stylesheet":
            self.stylesheets.append(attributes.get("href"))
        if tag == "script" and attributes.get("src"):
            self.scripts.append(attributes["src"])


class ViewerStaticTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.html = (VIEWER_ROOT / "index.html").read_text(encoding="utf-8")
        cls.javascript = (VIEWER_ROOT / "app.js").read_text(encoding="utf-8")
        cls.styles = (VIEWER_ROOT / "styles.css").read_text(encoding="utf-8")

    def test_html_uses_only_local_declared_assets(self):
        parser = AssetParser()
        parser.feed(self.html)
        self.assertEqual(["styles.css"], parser.stylesheets)
        self.assertEqual(["app.js"], parser.scripts)
        for relative in parser.stylesheets + parser.scripts:
            self.assertTrue((VIEWER_ROOT / relative).is_file())
        self.assertNotRegex(self.html, r"(?:src|href)=[\"']https?://")

    def test_catalog_content_is_not_injected_as_html_or_code(self):
        forbidden = (
            r"\.innerHTML\b",
            r"\.outerHTML\b",
            r"insertAdjacentHTML\s*\(",
            r"document\.write\s*\(",
            r"\beval\s*\(",
            r"\bnew\s+Function\b",
        )
        for pattern in forbidden:
            with self.subTest(pattern=pattern):
                self.assertNotRegex(self.javascript, pattern)
        self.assertIn("textContent", self.javascript)

    def test_catalog_and_deep_link_contract_is_present(self):
        required_fragments = (
            'const DATA_URL = "../catalog.ndjson"',
            '"#/record/"',
            '"#/case/"',
            "state.basisSources",
            "resolver.symbols",
            "resolver.classes",
            "extractCaseSection",
            "catalogCaseReferences",
        )
        for fragment in required_fragments:
            with self.subTest(fragment=fragment):
                self.assertIn(fragment, self.javascript)

    def test_case_source_is_rendered_as_preformatted_text(self):
        self.assertRegex(
            self.javascript,
            re.compile(r'element\("pre",\s*\{\s*className:\s*"case-source",\s*text:', re.S),
        )
        self.assertIn(".case-source", self.styles)


if __name__ == "__main__":
    unittest.main()
