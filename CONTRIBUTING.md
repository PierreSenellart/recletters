# Contributing

Thanks for considering a contribution.

## Build and test

```sh
sbt compile         # build
sbt test            # unit-level tests (no DB required)
```

DB-backed integration tests require a fresh PostgreSQL or MySQL/MariaDB
database; see `test/create_test_db.sh` and `docs/develop.md`.

## Branching and pull requests

- Work from a feature branch off `main`.
- Keep PRs focused; security fixes and architectural changes should land
  separately.
- Run `sbt test` before pushing.
- Reference the issue or section of `PLAN.md` (when present) the change
  addresses.

## Where the dragons are

- **Scala 3 unapply workaround**: `MainController.unapply` works around
  [scala/scala3#2335](https://github.com/scala/scala3/issues/2335). Leave it
  alone unless upgrading Scala.
- **Anorm `PrefixNaming`**: joined queries must alias columns with the
  prefix, see `RefereeRequestService.findAll`.
- **Per-DBMS Evolutions**: every schema change must ship two files
  (`N-postgres.sql` and `N-mysql.sql`) **and** be tested against both engines.
- **Twirl `Call` shadowing**: `models.Call` collides with
  `play.api.mvc.Call`. The collision is resolved in `build.sbt` via
  `TwirlKeys.templateImports`. If you add a new template-wide import, keep
  the same ordering.
- **No CSRF on token-authed routes**: `/submit`, `/lettert`, and all
  `/api/*` routes are explicitly `+ nocsrf` in `conf/routes` because the URL
  token or bearer header *is* the credential. Anything new that mutates
  state under session auth must keep CSRF on.
- **Crypto lives in Scala, not in SQL**: passwords use bcrypt, tokens use
  SHA-256 + `SecureRandom`. Do not reintroduce `pgcrypto`-isms; they break
  the MySQL build.
