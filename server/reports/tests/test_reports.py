import gzip
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import reports  # noqa: E402


class FakeGitHub:
    def __init__(self):
        self.calls = []

    def create_issue(self, title, body):
        self.calls.append((title, body))
        return {"number": len(self.calls), "url": f"https://github.com/x/y/issues/{len(self.calls)}"}


def body(**summary):
    summary.setdefault("appVersion", "1.0.0")
    return json.dumps({"summary": summary, "report": "full log\nPRIVATE-CHANNEL-DATA"}).encode()


class ReportsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        env = {"DATA_DIR": self.tmp.name, "RATE_PER_IP_HOUR": "2", "MAX_ISSUES_PER_DAY": "1",
               "GITHUB_TOKEN_FILE": "/nonexistent", "ADMIN_PASSWORD_FILE": "/nonexistent"}
        self.github = FakeGitHub()
        self.app = reports.App(reports.Config(env), github=self.github)

    def tearDown(self):
        self.tmp.cleanup()

    def test_stores_report_and_opens_issue_without_full_report(self):
        status, resp = self.app.submit(body(model="X1", reason="scan_failed", error="no tuner <b>"), "", "1.2.3.4")
        self.assertEqual(201, status)
        self.assertEqual(1, resp["issue"]["number"])
        stored = self.app.store.load(resp["id"])
        self.assertIn("PRIVATE-CHANNEL-DATA", stored["report"])
        title, issue = self.github.calls[0]
        self.assertTrue(title.startswith("[report] scan failed – X1"), title)
        self.assertNotIn("PRIVATE-CHANNEL-DATA", issue)
        self.assertNotIn("<b>", issue)

    def test_gzip_body(self):
        status, _ = self.app.submit(gzip.compress(body()), "gzip", "1.2.3.4")
        self.assertEqual(201, status)

    def test_rejects_invalid_bodies(self):
        self.assertEqual(400, self.app.submit(b"not json", "", "a")[0])
        self.assertEqual(400, self.app.submit(json.dumps({"summary": {}}).encode(), "", "b")[0])
        self.assertEqual(400, self.app.submit(b"\x1f\x8bxx", "gzip", "c")[0])

    def test_rate_limits(self):
        self.assertEqual(201, self.app.submit(body(), "", "ip")[0])
        self.assertEqual(201, self.app.submit(body(), "", "ip")[0])
        self.assertEqual(429, self.app.submit(body(), "", "ip")[0])
        # Daily issue cap is 1: the second report is stored without an issue.
        self.assertEqual(1, len(self.github.calls))

    def test_mentions_and_unknown_reason_are_neutralized(self):
        parsed = reports.parse_report(body(reason="hack", model="@depablo"))
        self.assertEqual("other", parsed["summary"]["reason"])
        self.assertNotIn("@depablo", reports.issue_body("id", parsed["summary"]))

    def test_gzip_bomb_rejected(self):
        bomb = gzip.compress(b"0" * (reports.MAX_DECOMPRESSED + 10))
        self.assertEqual(400, self.app.submit(bomb, "gzip", "d")[0])

    def test_admin_list_and_text(self):
        _, first = self.app.submit(body(model="A1", error="<script>x</script>"), "", "ip1")
        _, second = self.app.submit(body(model="B2"), "", "ip2")
        listed = self.app.store.list()
        self.assertEqual({first["id"], second["id"]}, {r["id"] for r in listed})
        page = reports.admin_page(listed)
        self.assertIn(f'/admin/reports/{first["id"]}.txt', page)
        self.assertNotIn("<script>", page)
        text = reports.report_text(first["id"], self.app.store.load(first["id"]))
        self.assertIn("model: A1", text)
        self.assertIn("PRIVATE-CHANNEL-DATA", text)

    def test_load_rejects_path_traversal(self):
        self.assertIsNone(self.app.store.load("../../etc/passwd"))

    def test_admin_auth(self):
        self.app.cfg.admin_password = "secret"
        import base64
        good = "Basic " + base64.b64encode(b"admin:secret").decode()
        bad = "Basic " + base64.b64encode(b"admin:wrong").decode()
        self.assertTrue(self.app.check_admin(good))
        self.assertFalse(self.app.check_admin(bad))
        self.assertFalse(self.app.check_admin(""))


if __name__ == "__main__":
    unittest.main()
