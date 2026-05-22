# Changelog

All notable changes to recletters will be recorded here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
starting from the first tagged OSS release.

## [Unreleased]

### Added
- First-class **call** model: per-call deadline and branding, supporting
  parallel and one-off calls (not just annual rounds).
- Plug-in importer architecture: shipped `CsvImporter`, `JsonApiImporter`,
  `HotCRPImporter`; `POST /api/dossiers/bulk` endpoint;
  `tools/hotcrp-import.py` companion script.
- **MySQL / MariaDB** support alongside PostgreSQL, including per-DBMS Play
  Evolutions files (`1-postgres.sql`, `1-mysql.sql`).
- i18n (English + French) for all UI and email strings.
- Docker + docker-compose setup with `postgres` and `mysql` profiles.
- `.deb` packaging via sbt-native-packager + systemd unit + sample reverse
  proxy configs (nginx, Caddy).
- Admin CLI: `tools.AddUser`, `tools.NewCall`.
- Test scaffolding (ScalaTest + scalatestplus-play); unit specs for
  `PasswordHasher`, `RequestStatus`, `Call`, and `CsvImporter`.

### Changed
- Schema is now managed by **Play Evolutions** (`conf/evolutions/default/`);
  the legacy `conf/database.sql` is removed.
- `dossier.year SMALLINT` → `dossier.call_id INTEGER NOT NULL REFERENCES
  call_(id)`. Migration script: `conf/migrate-from-legacy.sql`.
- All cryptography moved from SQL (`pgcrypto`) into Scala: **bcrypt** for
  passwords, **SHA-256 + SecureRandom** for tokens. The DB no longer
  requires the `pgcrypto` extension.
- `request_status` PostgreSQL native enum → portable
  `VARCHAR(16) NOT NULL CHECK (status IN (...))`.
- Referee tokens are now stored hashed (SHA-256) with an `expires_at`
  column; constant-time compare via `MessageDigest.isEqual`.
- Email bodies moved out of `MailerService` literals into `conf/messages`
  / `conf/messages.fr`.

### Security
- Session cookies set `Secure`, `HttpOnly`, `SameSite=Lax`.
- CSRF protection re-enabled globally; token-authed referee endpoints and
  bearer-authed API routes exempted via `+ nocsrf`.
- Security headers (`SecurityHeadersFilter`): CSP, X-Frame-Options=DENY,
  Referrer-Policy=same-origin, X-Content-Type-Options=nosniff, HSTS.
- Open-redirect fix on `/authenticate` (rejects paths not starting with
  a single `/`).
- Password-reset email-enumeration fix (constant response regardless of
  whether the address exists).
- File upload: 10 MiB cap + server-side PDF magic-byte check; letters served
  with `Content-Disposition: attachment` and `X-Content-Type-Options:
  nosniff`.

### Removed
- The Mill build (`build.sc`). sbt is the only supported build.
- `conf/database.sql` (replaced by Play Evolutions).
- The `dossier.year` column (replaced by `dossier.call_id`).
- `pgcrypto` dependency.
