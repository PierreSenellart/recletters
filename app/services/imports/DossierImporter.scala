package services.imports

import models.Call

/** A dossier imported from an external source. The fields mirror what a
  * committee user would otherwise type into the /add form, plus an
  * `externalRef` (typically the upstream system's primary key) to make
  * re-imports idempotent.
  */
case class ImportedDossier(
    externalRef: Option[String],
    name: String,
    url: Option[String],
    notes: Option[String],
    referees: Seq[ImportedReferee]
)

case class ImportedReferee(
    email: String,
    role: Option[String],
    notes: Option[String]
)

case class ImportResult(
    created: Int,
    updated: Int,
    skipped: Int
) {
  def total: Int = created + updated + skipped
  def +(o: ImportResult): ImportResult =
    ImportResult(created + o.created, updated + o.updated, skipped + o.skipped)
}

object ImportResult {
  val empty: ImportResult = ImportResult(0, 0, 0)
}

/** Importer contract. Implementations live under `services/imports/` and are
  * registered in `ImportRegistry`.
  *
  * Implementations should be stateless and side-effect-free from `fetch`;
  * persistence is handled by `ImportService` which calls
  * `DossierService.upsert` for each result.
  */
trait DossierImporter {
  def name: String
  def isEnabled: Boolean
  def fetch(call: Call): Seq[ImportedDossier]
}
