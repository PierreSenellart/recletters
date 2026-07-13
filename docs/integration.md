# Integration

`recletters` accepts dossiers (applicant + referee list) from external sources
via an importer plug-in architecture. Three importers are shipped:

- **CSV upload** (`csv`): committee-driven, via the `/import` UI.
- **JSON API** (`json-api`): `POST /api/dossiers/bulk`, bearer-authed.
- **HotCRP** (`hotcrp`): pulls from a sibling MySQL database.

## The `ImportedDossier` contract

```scala
case class ImportedDossier(
    externalRef: Option[String],   // upstream PK, drives idempotency
    name:        String,           // applicant display name
    url:         Option[String],   // link to the upstream record (optional)
    notes:       Option[String],   // free text shown to the committee
    referees:    Seq[ImportedReferee]
)

case class ImportedReferee(
    email: String,
    role:  Option[String],         // e.g. "supervisor", "external"
    notes: Option[String]
)
```

Imports are idempotent: re-importing the same `(call_id, externalRef)` updates
the existing dossier instead of creating a duplicate. Referees added by a
re-import are inserted; existing rows are updated in-place.

## CSV

Upload at `/import` from the committee UI. Expected columns:

```
external_ref, name, url, notes, referee_email, referee_role
```

One CSV row per `(dossier, referee)` pair. Multiple rows sharing the same
`external_ref` (or, when empty, the same `name`) merge into one dossier with
multiple referees.

## JSON API

```sh
curl -X POST https://letters.example.org/api/dossiers/bulk \
     -H "Authorization: Bearer $RECLETTERS_TOKEN" \
     -H "Content-Type: application/json" \
     -d '{
           "call": "award-2026",
           "dossiers": [
             { "externalRef": "42",
               "name": "Alice Example",
               "url":  "https://example.org/papers/42",
               "notes": "PhD candidate",
               "referees": [
                 { "email": "supervisor@x", "role": "supervisor" },
                 { "email": "external@y",   "role": "external" }
               ]
             }
           ]
         }'
```

The `call` field can be the call's `slug` or its numeric `id`.

The response is `{"created": n, "updated": n, "total": n}`.

## HotCRP

Two integration modes, pick one:

### In-app HotCRP plug-in

Enable the plug-in by setting these in `secrets.conf`:

```hocon
importers.hotcrp.enabled = true

# A second JDBC database identified as "hotcrp"; the plug-in opens it via DBApi.
db.hotcrp.driver   = "org.mariadb.jdbc.Driver"
db.hotcrp.url      = "jdbc:mariadb://localhost/hotcrp"
db.hotcrp.username = "..."
db.hotcrp.password = "..."

# Map HotCRP option ids to recletters referee roles. Each key is a HotCRP
# `PaperOption.optionId`; the value is a free-text role.
importers.hotcrp.option-mapping {
  4  = "supervisor"
  5  = "external"
  6  = "external"
  24 = "industry"
}

# Optional: link each imported dossier back to its HotCRP paper. The dossier
# name in the requests list becomes a link to this URL, with `{id}` replaced by
# the HotCRP paperId. Unset leaves dossiers link-less.
importers.hotcrp.paper-url = "https://host/prix/paper/{id}"
```

Committee users then run the importer from `/import`. To drive it headlessly
(e.g. from cron), the same in-app importer is exposed as a bearer-authed
endpoint:

```sh
curl -X POST -H "Authorization: Bearer $RECLETTERS_TOKEN" \
     "https://letters.example.org/api/import/run/hotcrp?call=award-2026"
```

`call` is the call slug or numeric id. Pair it with
`POST /api/sendRequestEmails?call=…` to import and notify referees on a
schedule. Unlike the companion script below, this reuses the credentials in
`secrets.conf` — no need to duplicate the HotCRP connection anywhere.

### Companion script (cron + bearer)

For sites that prefer to keep HotCRP credentials out of the recletters
process, run the companion script under cron:

```sh
HOTCRP_DB_URL=mysql://user:pass@host/hotcrp \
RECLETTERS_URL=https://letters.example.org \
RECLETTERS_TOKEN=$(cat /etc/recletters/api_token) \
RECLETTERS_CALL=award-2026 \
OPTION_MAPPING='{"4":"supervisor","5":"external","24":"industry"}' \
python3 tools/hotcrp-import.py
```

## Writing a new importer

About 100 lines. Subclass `services.imports.DossierImporter`:

```scala
@Singleton
class MyImporter @Inject() (config: Configuration) extends DossierImporter {
  val name = "my-importer"
  def isEnabled = config.getOptional[Boolean]("importers.mine.enabled").getOrElse(false)
  def fetch(call: Call): Seq[ImportedDossier] = {
    // ... pull from upstream, transform into ImportedDossier instances
  }
}
```

Then add it to `services.imports.ImportRegistry`:

```scala
@Singleton
class ImportRegistry @Inject() (
    csv:  CsvImporter,
    json: JsonApiImporter,
    hot:  HotCRPImporter,
    mine: MyImporter
) {
  val all: Seq[DossierImporter] = Seq(csv, json, hot, mine)
  // ...
}
```

Add a `importers.mine.*` block to `application.conf.template`. Done.
