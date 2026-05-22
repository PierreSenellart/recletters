# recletters

A small web application for collecting recommendation letters from referees.
Applicants (or an importer) register dossiers and referee email addresses; the
site emails referees a tokenised submission link; referees upload a PDF (or
decline); committee members later download the letters.

Built with Play Framework (Scala 3) and Anorm. Runs against PostgreSQL or
MySQL/MariaDB.

## Is this for you?

`recletters` is intended for a single institution or department running one or
several recommendation-letter calls. A "call" can be:

- an annual award round (the original use case);
- a hiring committee for a specific position;
- a grant or fellowship call;
- a graduate-admission cycle.

If you need any of the following, this is **not** the right project for you:

- multi-tenant SaaS hosting of many institutions on a single instance;
- letter storage outside the application database (S3, filesystem);
- a full applicant self-signup portal;
- a complete REST API beyond the bulk-ingest endpoint.

## Quick start

The fastest way to try recletters locally:

```sh
docker compose --profile postgres up --build
# wait ~30s for Play Evolutions to run, then open http://localhost:9000
# outbound mail is caught at http://localhost:8025 (MailHog)
```

To create a first committee user in the running container:

```sh
docker compose exec app-postgres /opt/recletters/bin/recletters \
    -main tools.AddUser you@example.org First Last "your-password"
```

## Deployment targets

| Target              | Audience                       | See                    |
| ------------------- | ------------------------------ | ---------------------- |
| `sbt run`           | development / contributors     | `docs/develop.md`      |
| `docker compose up` | evaluation, self-contained dev | this README + `Dockerfile` |
| `sbt stage`         | traditional Linux installs     | `docs/install.md`      |
| `.deb` package      | Debian / Ubuntu production     | `docs/install.md`      |

## Database

PostgreSQL is the primary supported engine; MySQL ≥ 8 and MariaDB ≥ 10.5 are
first-class secondaries. Schema is applied by Play Evolutions; flavour is
selected by the symlink at `conf/evolutions/default/1.sql`:

```sh
# pick a flavour
( cd conf/evolutions/default && ln -sf 1-postgres.sql 1.sql )    # default
( cd conf/evolutions/default && ln -sf 1-mysql.sql   1.sql )
```

If you are upgrading an existing production database from the legacy
(year-based, `pgcrypto`, native enum) schema, see `conf/migrate-from-legacy.sql`.

## Integration

Dossiers can be loaded via:

- the manual `/add` form (committee user);
- the `/import` UI (CSV upload);
- the bearer-authed `POST /api/dossiers/bulk` endpoint;
- the in-app **HotCRP plug-in** (reads a sibling MySQL instance);
- the companion `tools/hotcrp-import.py` script (cron + bearer).

See `docs/integration.md` for the `ImportedDossier` contract and how to write a
new importer.

## License

MIT. See `LICENSE`.
