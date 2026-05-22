# Developing recletters

## Prerequisites

- Java 17 or 21.
- sbt 1.10+ (provided by `sbt` runner via `sbtopts`).
- A local PostgreSQL or MySQL instance, or use the docker-compose stack for
  the DB only.

## Bootstrap

```sh
git clone https://github.com/<your-fork>/recletters.git
cd recletters

# 1. Pick a DB flavour (defaults to PostgreSQL).
( cd conf/evolutions/default && ln -sf 1-postgres.sql 1.sql )

# 2. Config.
cp conf/application.conf.template conf/application.conf
cp conf/secrets.conf.template     conf/secrets.conf
$EDITOR conf/application.conf conf/secrets.conf

# 3. Create the DB.
createdb recletters_dev    # or `mysql -e 'CREATE DATABASE recletters_dev;'`

# 4. Start. Play Evolutions auto-applies on first request.
sbt run
```

Open <http://localhost:9000>. Apply Evolutions when prompted.

## Tests

The full suite (unit + HTTP-layer integration) runs against a real database.

```sh
make test            # alias for: make test-pg
make test-pg         # PostgreSQL via peer auth on the Unix socket
make test-mysql      # MySQL / MariaDB via socket auth
make test-all        # both, back-to-back
```

Each target (re)creates a `recletters_test` database, flips the
Evolutions symlink to the right flavour, then runs `sbt test` with the
matching `-Dconfig.resource=test.conf` / `test-mysql.conf`. Tests fork the
JVM so the system property reaches the test classpath (see `build.sbt`).

### Database prerequisites

- **PostgreSQL**: the current Unix user must own a CREATEDB-capable role
  reachable via the Unix socket (peer auth). `psql -d postgres` from the
  shell must work without a password. No `pgcrypto` extension is required.
- **MySQL / MariaDB**: the current Unix user must have a matching
  `'username'@'localhost'` MySQL user authenticated with the
  `auth_socket` (MySQL ≥ 8) or `unix_socket` (MariaDB) plugin, with
  privileges on the two test-database name patterns: `recletters_%`
  (the application DB) and `hotcrp_%` (the fixture DB used by the
  HotCRPImporter spec). Bootstrap once as MySQL root:

  ```sql
  CREATE USER IF NOT EXISTS 'yourname'@'localhost' IDENTIFIED WITH auth_socket;
  GRANT ALL PRIVILEGES ON `recletters\_%`.* TO 'yourname'@'localhost';
  GRANT ALL PRIVILEGES ON `hotcrp\_%`.*     TO 'yourname'@'localhost';
  FLUSH PRIVILEGES;
  ```

  Verify (as your Unix user, no password):

  ```sh
  mysql -e "CREATE DATABASE recletters_probe; DROP DATABASE recletters_probe;"
  mysql -e "CREATE DATABASE hotcrp_probe;     DROP DATABASE hotcrp_probe;"
  ```

### How it works internally

`conf/test.conf` (PostgreSQL) and `conf/test-mysql.conf` (MySQL/MariaDB)
configure `db.default.*` to connect over the appropriate Unix socket. The
MariaDB Connector/J requires JNA on the classpath for Unix-socket support;
that dependency is pulled in via `build.sbt`.

Per-test isolation: `test/helpers/DBFixtures.scala` truncates all app
tables (`RESTART IDENTITY CASCADE` on PG, `DELETE` + `AUTO_INCREMENT = 1`
on MySQL) before every spec and inserts a small fixture set. `Test /
parallelExecution := false` keeps specs serial since they share the DB.

## Translating

UI and email strings live in `conf/messages` (English) and `conf/messages.fr`
(French). To add a language, create `conf/messages.xx` and add `"xx"` to
`play.i18n.langs` in `application.conf`.

## Dragons

- **Scala 3 product destructuring**: `MainController.unapply` is a workaround
  for [scala/scala3#2335](https://github.com/scala/scala3/issues/2335).
- **Anorm `PrefixNaming`**: joined queries alias columns as `prefix.field`;
  the row parser uses `Macro.namedParser[T](new PrefixNaming(prefix))`.
- **Per-DBMS Evolutions**: `conf/evolutions/default/1.sql` is a symlink to
  either `1-postgres.sql` or `1-mysql.sql`. Make sure both flavours change
  together when you add a new evolution.
- **Twirl Call shadowing**: `models.Call` is imported into templates after
  `play.api.mvc.{Call => _, _}` so it shadows the mvc reverse-routing class.
  See `build.sbt`.
- **Twirl email bodies**: emails are not Twirl templates yet (they are still
  composed via `Messages.apply(...)` with positional args); adding Twirl
  email templates is a documented follow-up.
