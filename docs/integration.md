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

They are also *change-detecting*. A dossier whose name, url and notes already
equal what upstream sends is not written at all and is counted as `unchanged`;
only a real difference counts as `updated` (a referee whose role or notes moved
counts too). Re-running an importer is therefore cheap and quiet, which is what
makes the two refresh paths below usable:

- **Refresh button** — `/requests` shows *Refresh dossiers from upstream* for
  committee users whenever at least one enabled importer can pull on its own.
  It re-reads every such upstream for the current call and reports
  `n new, n updated, n unchanged`. It writes nothing else: statuses, tokens and
  letters are untouched, and no mail is sent.
- **Scheduled refresh** — the same thing on a timer, via the bearer-authed
  endpoint (see [Scheduled refresh](#scheduled-refresh)).

This is what keeps a dossier from freezing at the state it had on first import:
if a candidate fixes their submission title, or the committee corrects a referee
address upstream, the next refresh picks it up.

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

The response is `{"created": n, "updated": n, "unchanged": n, "total": n}`.
`updated` counts only dossiers whose metadata really differed from what was
already stored; re-posting an identical payload reports it as `unchanged` and
writes nothing.

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

Only `db.hotcrp.password` is genuinely secret; the rest of the block above is
non-secret and can just as well live in `application.conf` (that is where the
shipped `application.conf.template` documents `importers.hotcrp.paper-url`). The
two files are merged at load time, so placement is a matter of convention.

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

### Scheduled refresh

The endpoint answers with a one-line summary whose shape is stable:

```
hotcrp: 42 (0 new, 0 updated, 42 unchanged)
```

Because an unchanged dossier is never written, running this hourly is harmless,
and the counts double as a drift alarm: cron mails you only when the upstream
actually moved.

```cron
# /etc/cron.d/recletters-refresh
17 * * * * recletters out=$(curl -sS -X POST \
    -H "Authorization: Bearer $(cat /etc/recletters/api_token)" \
    "https://letters.example.org/api/import/run/hotcrp?call=award-2026"); \
  case "$out" in *"0 new, 0 updated"*) ;; *) echo "$out" ;; esac
```

Every created or updated dossier is also logged at INFO by
`services.imports.ImportService`, so `journalctl -u recletters | grep "^.*import:"`
shows exactly which dossiers changed and when.

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
  // Set canPull when fetch() can go and get dossiers on its own: that is what
  // puts the importer behind the /requests refresh button and refreshAll.
  override val canPull = true
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
