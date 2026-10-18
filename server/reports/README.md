# bapTV report service

A small service that receives diagnostics reports sent from the bapTV app (only after the user
agrees) and opens a GitHub issue for each one. Python standard library only, packaged with Docker.

- `POST /v1/report` – JSON `{"summary": {...}, "report": "<full text>"}`, optionally gzip-compressed
  (`Content-Encoding: gzip`). Answers `201 {"id": "...", "issue": {"number": 12, "url": "..."}}`.
- `GET /v1/reports/<id>` – the stored report, HTTP Basic auth with the admin password (any user name).
- `GET /healthz` – health check.

Summary fields (all strings, optional unless noted): `appVersion` (required), `appVersionCode`,
`manufacturer`, `model`, `android`, `sdk`, `tuner`, `reason` (`user`, `scan_failed`, `no_tuner`,
`crash`, `playback`, `other`), `error`, `region`, `language`.

## Privacy

Only the short summary goes into the public GitHub issue (values are length-limited and Markdown,
HTML and @-mentions are neutralized). The full report – log, channel list, device details – stays on
the server in the `reports-data` volume and is deleted after `RETENTION_DAYS` (default 180).

## Abuse limits

- body up to 2 MB (8 MB after decompression);
- `RATE_PER_IP_HOUR` reports per client IP (default 5);
- at most `MAX_ISSUES_PER_DAY` GitHub issues per day (default 30) – further reports are still stored.

## Deployment

1. Create a fine-grained GitHub token: *Settings → Developer settings → Fine-grained tokens*,
   repository access *Only select repositories → bapTV*, permission *Issues: Read and write*.
2. On the server:

   ```sh
   git clone https://github.com/depablo/bapTV.git && cd bapTV/server/reports
   mkdir -p secrets && chmod 700 secrets
   printf '%s' 'github_pat_…' > secrets/github_token
   openssl rand -base64 24 > secrets/admin_password
   # The service runs as uid 10001 inside the container and must be able to read the secrets.
   sudo chown 10001:10001 secrets/* && chmod 600 secrets/*
   docker compose up -d --build
   curl -s http://127.0.0.1:8087/healthz
   ```

3. Point a DNS name (for example `reports.bonsaponsi.com`) at the server and proxy it to
   `127.0.0.1:8087` with HTTPS. Caddy:

   ```
   reports.bonsaponsi.com {
       reverse_proxy 127.0.0.1:8087
   }
   ```

   nginx (with a certificate from certbot):

   ```
   server {
       listen 443 ssl;
       server_name reports.bonsaponsi.com;
       client_max_body_size 3m;
       location / {
           proxy_pass http://127.0.0.1:8087;
           proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
       }
   }
   ```

Settings can be overridden in a `.env` file next to `compose.yml` (`GITHUB_REPO`, `GITHUB_LABELS`,
`HOST_PORT`, `RATE_PER_IP_HOUR`, `MAX_ISSUES_PER_DAY`, `RETENTION_DAYS`). `GITHUB_LABELS` must name
labels that already exist in the repository.

## Tests

```sh
python3 -m unittest discover -s tests
```
