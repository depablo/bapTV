#!/usr/bin/env python3
"""
bapTV report intake service.

The app sends a diagnostics report (only after the user agrees) to POST /v1/report. The service
stores the full report on disk and opens a GitHub issue with a short, non-sensitive summary.
The full report (log, channel list) never goes to GitHub; it can be read only with the admin
password at GET /v1/reports/<id>.

Standard library only. Configuration through environment variables; secrets from files
(Docker Compose secrets).
"""
import base64
import hmac
import json
import logging
import os
import re
import secrets
import threading
import time
import urllib.error
import urllib.request
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


def _env_int(name, default):
    return int(os.environ.get(name, default))


def _read_secret(path):
    try:
        return Path(path).read_text().strip() or None
    except FileNotFoundError:
        return None
    except OSError as e:
        # Typically the secret file on the host is not readable by the container user (uid 10001).
        logging.warning("cannot read secret %s: %s", path, e)
        return None


class Config:
    def __init__(self, env=os.environ):
        self.data_dir = Path(env.get("DATA_DIR", "/data"))
        self.port = int(env.get("PORT", "8080"))
        self.github_repo = env.get("GITHUB_REPO", "")
        self.github_labels = [l for l in env.get("GITHUB_LABELS", "").split(",") if l.strip()]
        self.github_token_file = env.get("GITHUB_TOKEN_FILE", "/run/secrets/github_token")
        self.admin_password = _read_secret(env.get("ADMIN_PASSWORD_FILE", "/run/secrets/admin_password"))
        self.public_url = env.get("PUBLIC_URL", "").rstrip("/")
        self.trust_proxy = env.get("TRUST_PROXY", "0") == "1"
        self.max_body = int(env.get("MAX_BODY_BYTES", 2 * 1024 * 1024))
        self.per_ip_per_hour = int(env.get("RATE_PER_IP_HOUR", "5"))
        self.issues_per_day = int(env.get("MAX_ISSUES_PER_DAY", "30"))
        self.retention_days = int(env.get("RETENTION_DAYS", "180"))


# Summary fields that may appear in the public GitHub issue, with their maximum length.
SUMMARY_FIELDS = {
    "appVersion": 32, "appVersionCode": 12, "manufacturer": 64, "model": 64, "android": 16, "sdk": 4,
    "tuner": 120, "reason": 64, "error": 300, "region": 8, "language": 16,
}
REASONS = {"user", "scan_failed", "no_tuner", "crash", "playback", "other"}
ID_RE = re.compile(r"^[A-Za-z0-9_-]{16,64}$")


class ValidationError(Exception):
    pass


MAX_DECOMPRESSED = 8 * 1024 * 1024


def _gunzip(raw: bytes) -> bytes:
    """Decompresses with a size cap (a small gzip body must not expand into gigabytes)."""
    d = zlib.decompressobj(16 + zlib.MAX_WBITS)
    try:
        out = d.decompress(raw, MAX_DECOMPRESSED + 1)
    except zlib.error as e:
        raise ValidationError("invalid gzip body") from e
    if len(out) > MAX_DECOMPRESSED or d.unconsumed_tail:
        raise ValidationError("report too large")
    if not d.eof:
        raise ValidationError("invalid gzip body")
    return out


def parse_report(raw: bytes, content_encoding: str = "") -> dict:
    """Decodes and validates a report body: {"summary": {...}, "report": "<full text>"}."""
    if content_encoding.lower() == "gzip":
        raw = _gunzip(raw)
    try:
        body = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as e:
        raise ValidationError("body must be UTF-8 JSON") from e
    if not isinstance(body, dict) or not isinstance(body.get("summary"), dict):
        raise ValidationError("missing summary object")
    report = body.get("report", "")
    if not isinstance(report, str):
        raise ValidationError("report must be a string")
    summary = {}
    for key, limit in SUMMARY_FIELDS.items():
        value = body["summary"].get(key)
        if value is None:
            continue
        value = re.sub(r"[\x00-\x1f\x7f]", " ", str(value)).strip()[:limit]
        if value:
            summary[key] = value
    if not summary.get("appVersion"):
        raise ValidationError("summary.appVersion is required")
    if summary.get("reason", "user") not in REASONS:
        summary["reason"] = "other"
    return {"summary": summary, "report": report}


def _md(text: str) -> str:
    """Neutralizes Markdown/HTML and @-mentions in values that go into the issue."""
    text = re.sub(r"[<>`|*_\[\]#]", " ", text)
    return text.replace("@", "@​")


def issue_title(summary: dict) -> str:
    # Titles are plain text on GitHub (no Markdown, no mention notifications); values are already length-limited.
    reason = summary.get("reason", "user").replace("_", " ")
    device = " ".join(x for x in (summary.get("manufacturer"), summary.get("model")) if x) or "unknown device"
    return f"[report] {reason} – {device}, {summary['appVersion']}"[:200]


def issue_body(report_id: str, summary: dict) -> str:
    rows = "\n".join(f"| {k} | {_md(v)} |" for k, v in summary.items() if k != "error")
    error = summary.get("error")
    error_block = f"\n**Last error:**\n\n    {_md(error)}\n" if error else ""
    return (
        "Automatic report sent from the app by a user.\n\n"
        "| Field | Value |\n|---|---|\n" + rows + "\n" + error_block +
        f"\nFull report (log, channel list) is stored privately on the report server: `{report_id}`.\n"
    )


class RateLimiter:
    def __init__(self, limit: int, window_s: float):
        self.limit, self.window = limit, window_s
        self.hits = {}
        self.lock = threading.Lock()

    def allow(self, key: str, now: float = None) -> bool:
        now = time.time() if now is None else now
        with self.lock:
            recent = [t for t in self.hits.get(key, []) if now - t < self.window]
            if len(recent) >= self.limit:
                self.hits[key] = recent
                return False
            recent.append(now)
            self.hits[key] = recent
            if len(self.hits) > 10000:  # drop idle keys
                self.hits = {k: v for k, v in self.hits.items() if v and now - v[-1] < self.window}
            return True


class GitHub:
    def __init__(self, cfg: Config):
        self.cfg = cfg

    def token(self):
        # Read on every use: a token added or rotated later works without a restart.
        return _read_secret(self.cfg.github_token_file)

    def create_issue(self, title: str, body: str):
        token = self.token()
        if not (token and self.cfg.github_repo):
            logging.warning("no GitHub token or repo – issue not created")
            return None
        payload = {"title": title, "body": body}
        if self.cfg.github_labels:
            payload["labels"] = self.cfg.github_labels
        req = urllib.request.Request(
            f"https://api.github.com/repos/{self.cfg.github_repo}/issues",
            data=json.dumps(payload).encode(),
            method="POST",
            headers={
                "Authorization": f"Bearer {token}",
                "Accept": "application/vnd.github+json",
                "X-GitHub-Api-Version": "2022-11-28",
                "User-Agent": "baptv-reports",
            },
        )
        try:
            with urllib.request.urlopen(req, timeout=15) as resp:
                data = json.loads(resp.read())
                return {"number": data["number"], "url": data["html_url"]}
        except urllib.error.HTTPError as e:
            detail = e.read(500).decode("utf-8", "replace")
            logging.warning("GitHub issue failed: HTTP %s %s", e.code, detail)
            return None
        except (urllib.error.URLError, KeyError, ValueError) as e:
            logging.warning("GitHub issue failed: %s", e)
            return None


class Store:
    def __init__(self, cfg: Config):
        self.dir = cfg.data_dir / "reports"
        self.dir.mkdir(parents=True, exist_ok=True)
        self.retention_s = cfg.retention_days * 86400
        self.lock = threading.Lock()

    def save(self, record: dict) -> str:
        report_id = secrets.token_urlsafe(18)
        tmp = self.dir / f".{report_id}.tmp"
        tmp.write_text(json.dumps(record, ensure_ascii=False))
        tmp.replace(self.dir / f"{report_id}.json")
        return report_id

    def update(self, report_id: str, **fields):
        path = self.dir / f"{report_id}.json"
        with self.lock:
            record = json.loads(path.read_text())
            record.update(fields)
            path.write_text(json.dumps(record, ensure_ascii=False))

    def load(self, report_id: str):
        if not ID_RE.match(report_id):
            return None
        path = self.dir / f"{report_id}.json"
        return json.loads(path.read_text()) if path.exists() else None

    def prune(self, now: float = None):
        now = time.time() if now is None else now
        removed = 0
        for path in self.dir.glob("*.json"):
            if now - path.stat().st_mtime > self.retention_s:
                path.unlink(missing_ok=True)
                removed += 1
        if removed:
            logging.info("pruned %d old reports", removed)


class App:
    def __init__(self, cfg: Config, github=None):
        self.cfg = cfg
        self.store = Store(cfg)
        self.github = github or GitHub(cfg)
        self.per_ip = RateLimiter(cfg.per_ip_per_hour, 3600)
        self.issues = RateLimiter(cfg.issues_per_day, 86400)

    def submit(self, raw: bytes, encoding: str, client_ip: str):
        """Returns (status, response dict)."""
        if not self.per_ip.allow(client_ip):
            return 429, {"error": "too many reports, try again later"}
        try:
            parsed = parse_report(raw, encoding)
        except ValidationError as e:
            return 400, {"error": str(e)}
        record = {"received": int(time.time()), "summary": parsed["summary"], "report": parsed["report"]}
        report_id = self.store.save(record)
        logging.info("report %s: %s", report_id, parsed["summary"].get("reason", "user"))
        response = {"id": report_id}
        if self.issues.allow("global"):
            issue = self.github.create_issue(issue_title(parsed["summary"]), issue_body(report_id, parsed["summary"]))
            if issue:
                self.store.update(report_id, issue=issue)
                response["issue"] = issue
        return 201, response

    def check_admin(self, header: str) -> bool:
        if not self.cfg.admin_password or not header or not header.startswith("Basic "):
            return False
        try:
            _, _, password = base64.b64decode(header[6:]).decode().partition(":")
        except (ValueError, UnicodeDecodeError):
            return False
        return hmac.compare_digest(password, self.cfg.admin_password)


def make_handler(app: App):
    class Handler(BaseHTTPRequestHandler):
        server_version = "baptv-reports"
        sys_version = ""

        def log_message(self, fmt, *args):
            logging.info("%s %s", self.client_ip(), fmt % args)

        def client_ip(self):
            if app.cfg.trust_proxy:
                forwarded = self.headers.get("X-Forwarded-For", "")
                if forwarded:
                    return forwarded.split(",")[-1].strip()
            return self.client_address[0]

        def send_json(self, status, obj, extra=None):
            data = json.dumps(obj, ensure_ascii=False).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            for k, v in (extra or {}).items():
                self.send_header(k, v)
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            if self.path == "/healthz":
                return self.send_json(200, {"ok": True, "github": bool(app.github.token() and app.cfg.github_repo)})
            if self.path.startswith("/v1/reports/"):
                if not app.check_admin(self.headers.get("Authorization", "")):
                    return self.send_json(401, {"error": "authentication required"},
                                          {"WWW-Authenticate": 'Basic realm="baptv-reports"'})
                record = app.store.load(self.path.rsplit("/", 1)[-1])
                return self.send_json(200, record) if record else self.send_json(404, {"error": "not found"})
            self.send_json(404, {"error": "not found"})

        def do_POST(self):
            if self.path != "/v1/report":
                return self.send_json(404, {"error": "not found"})
            try:
                length = int(self.headers.get("Content-Length", "-1"))
            except ValueError:
                length = -1
            if length < 0:
                return self.send_json(411, {"error": "Content-Length required"})
            if length > app.cfg.max_body:
                return self.send_json(413, {"error": "report too large"})
            raw = self.rfile.read(length)
            status, body = app.submit(raw, self.headers.get("Content-Encoding", ""), self.client_ip())
            self.send_json(status, body)

    return Handler


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    cfg = Config()
    app = App(cfg)
    if not GitHub(cfg).token():
        logging.warning("no GitHub token yet – reports are stored but no issues are opened")
    if not cfg.admin_password:
        logging.warning("no admin password – stored reports cannot be read over HTTP")

    def prune_loop():
        while True:
            app.store.prune()
            time.sleep(86400)

    threading.Thread(target=prune_loop, daemon=True).start()
    server = ThreadingHTTPServer(("0.0.0.0", cfg.port), make_handler(app))
    logging.info("listening on :%d, repo %s", cfg.port, cfg.github_repo or "-")
    server.serve_forever()


if __name__ == "__main__":
    main()
