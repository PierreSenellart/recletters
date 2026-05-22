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

After picking, switch the active Evolutions flavour:

```sh
cd conf/evolutions/default
ln -sf 1-postgres.sql 1.sql       # PostgreSQL
# or
ln -sf 1-mysql.sql    1.sql       # MySQL / MariaDB
```

This must be done **before** the first start so Play Evolutions sees the right
file.

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
sudo apt install ./target/recletters_*.deb  # postinst creates user + dirs
sudo $EDITOR /etc/recletters/application.conf /etc/recletters/secrets.conf
sudo systemctl restart recletters
```

The `.deb` install includes the systemd unit, creates the `recletters` system
user, and creates `/etc/recletters/` and `/var/lib/recletters/`. Upgrades
preserve operator config.

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

# from .deb install:
recletters -main tools.AddUser you@example.org First Last "password"
```

## First call

```sh
recletters -main tools.NewCall \
    'award-2026' 'My Award 2026' 2026-01-15
```

`recletters` selects the active call automatically (most recent open
non-archived call); the call selector at the top of the UI lets committee
members switch between concurrent and historical calls.
