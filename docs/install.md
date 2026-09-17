# Installing recletters

Three production paths and one evaluation path. Pick the one that matches your
infrastructure.

## Prerequisites (all paths)

- Java 17 or 21.
- A database: PostgreSQL ≥ 13, OR MySQL ≥ 8 / MariaDB ≥ 10.5.
- An SMTP relay (optional in dev; use MailHog if running via Docker).
- A reverse proxy that terminates TLS (nginx or Caddy: sample configs in
  `docs/deploy/`).

## Choose a database

Both engines are first-class. The picking criteria:

- **PostgreSQL**: the engine the project was first developed against. The
  schema is portable (no `pgcrypto`, no native enum) so there is no longer a
  real difference, but it remains the better-tested path.
- **MySQL / MariaDB**: choose this if you already operate MySQL, notably any
  institution running HotCRP (which mandates MySQL). You can co-locate
  recletters and HotCRP in the same instance and run the in-app HotCRP
  importer against a sibling schema.

No further action is needed to pick the Evolutions flavour: the right SQL
(`<n>-postgres.sql` or `<n>-mysql.sql`) is selected at startup from the JDBC
driver you set in `db.default.driver`.

## Path A: Docker (evaluation)

```sh
docker compose --profile postgres up --build
# or
docker compose --profile mysql    up --build
```

Outbound mail is caught at <http://localhost:8025> (MailHog). Open
<http://localhost:9000> and create a first user:

```sh
docker compose exec app-postgres /opt/recletters/bin/recletters \
    -main tools.AddUser you@example.org First Last "your-password"
```

## Path B: `sbt stage` (traditional Linux install)

```sh
sbt stage                              # produces target/universal/stage/
rsync -av target/universal/stage/ \
      deploy@host:/opt/recletters/
ssh deploy@host
sudo cp /opt/recletters/docs/deploy/recletters.service /etc/systemd/system/
sudo useradd --system --home /opt/recletters recletters
sudo mkdir -p /etc/recletters /var/lib/recletters
sudo chown recletters: /etc/recletters /var/lib/recletters
sudo cp conf/application.conf.template /etc/recletters/application.conf
sudo cp conf/secrets.conf.template     /etc/recletters/secrets.conf
sudo $EDITOR /etc/recletters/application.conf /etc/recletters/secrets.conf
sudo systemctl daemon-reload
sudo systemctl enable --now recletters
```

## Path C: `.deb` package (production Debian/Ubuntu)

```sh
sbt debian:packageBin                       # builds target/recletters_*.deb
sudo apt install ./target/recletters_*.deb  # postinst creates the user
```

CI also builds the package from a clean checkout (artifact `recletters-deb`),
which avoids shipping anything that happens to lie in a working tree.

The `.deb` installs the application under `/usr/share/recletters/`, a systemd
unit, and the `recletters` system user. It also creates `/etc/recletters`, but
as a **symbolic link to `/usr/share/recletters/conf/`**, the directory that
holds the files shipped with the application (`routes`, `messages`, the
evolutions, the two `*.template` files). Do not keep your site configuration
there:

- every upgrade rewrites files in that directory;
- a backup of `/etc` stores the link, not the files behind it, so the
  configuration and the secrets would not be backed up.

Keep the two site files in a directory of their own, and point the service at
it:

```sh
sudo install -d -m 750 -o root -g recletters /etc/recletters-site
sudo install -m 640 -o root -g recletters \
     /usr/share/recletters/conf/application.conf.template \
     /etc/recletters-site/application.conf
sudo install -m 640 -o root -g recletters \
     /usr/share/recletters/conf/secrets.conf.template \
     /etc/recletters-site/secrets.conf
sudo $EDITOR /etc/recletters-site/application.conf /etc/recletters-site/secrets.conf
```

Then set the options of the service in `/etc/default/recletters` (a
configuration file of the package: dpkg keeps your version on upgrade):

```sh
JAVA_OPTS="-Dconfig.file=/etc/recletters-site/application.conf -Dhttp.port=9000 -Dpidfile.path=/dev/null"
```

```sh
sudo systemctl restart recletters
journalctl -u recletters -n 30              # "Application started (Prod)"
```

`include "secrets.conf"` in `application.conf` is resolved next to the file
that includes it, so it finds `/etc/recletters-site/secrets.conf`. Check that
`/etc/recletters-site/` is covered by your backups: `secrets.conf` holds the
only copy of the application key, the database password and the API token.

### Serving under a path prefix

If the reverse proxy forwards a prefix unchanged (e.g.,
`ProxyPass /letters http://127.0.0.1:9000/letters`), tell the application
about it, and include the prefix in `site_url`:

```
play.http.context = "/letters"
site_url          = "https://example.org/letters"
```

Every route then lives under the prefix, the JSON API included
(`/letters/api/...`). List the public host name, and any name local jobs use
to call the service directly, in `play.filters.hosts.allowed`.

## Upgrading

```sh
pg_dump DATABASE > recletters-before-upgrade.sql     # or mysqldump
sudo apt install ./recletters_*.deb                  # restarts the service
journalctl -u recletters -n 30
curl -s -o /dev/null -w '%{http_code}\n' https://HOST/login   # expect 200
```

- The configuration is read once, at startup. A restart, whether or not it
  comes with an upgrade, is therefore the moment a problem in the
  configuration files shows up: always check the journal and load the login
  page, which needs the database, the CSRF filter and the path prefix all at
  once.
- Schema changes ship as Play Evolutions and are applied at startup when
  `play.evolutions.db.default.autoApply = true` (the template default). With
  `autoApply = false`, or in a configuration file that predates the template
  and has no `play.evolutions` block, the service refuses to start with
  “Database 'default' needs evolution!” and prints the pending SQL in the
  journal: apply it by setting `autoApply`, not by hand, so that
  `play_evolutions` stays in step.
- Keep `autoApplyDowns = false`. Play then stops, and does not drop anything,
  if it ever believes an applied evolution must be undone.
- A configuration file that predates `application.conf.template` is best
  rebuilt from the template than patched: the template carries the
  `include "secrets.conf"` line, the evolutions block, the security headers
  and the session settings, and leaves the CSRF filter enabled, which every
  form of the application now requires.

## Path D: `sbt run` (development)

See `docs/develop.md`.

## Reverse proxy and TLS

See `docs/deploy/nginx.conf.sample` and `docs/deploy/Caddyfile.sample`.
HSTS is enabled by default in `application.conf.template`, so only serve over
HTTPS in production.

## First committee user

```sh
# from a staged install:
/opt/recletters/bin/recletters -main tools.AddUser \
    you@example.org First Last "password-from-cli-or-stdin"

# from .deb install (as a user allowed to read the site configuration):
sudo -u recletters recletters \
    -Dconfig.file=/etc/recletters-site/application.conf \
    -main tools.AddUser you@example.org First Last "password"
```

## First call

```sh
sudo -u recletters recletters \
    -Dconfig.file=/etc/recletters-site/application.conf \
    -main tools.NewCall 'award-2026' 'My Award 2026' 2026-01-15
```

`recletters` selects the active call automatically (most recent open
non-archived call); the call selector at the top of the UI lets committee
members switch between concurrent and historical calls.
