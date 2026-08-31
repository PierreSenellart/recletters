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

/** Counts for one import run. `updated` is the number of dossiers whose
  * metadata (or whose referee roles/notes) really differed from what upstream
  * now holds; `unchanged` is what was re-read and found identical. A scheduled
  * run that reports `0 new, 0 updated` did nothing, which is what makes these
  * counts usable as a drift alarm.
  */
case class ImportResult(
    created: Int,
    updated: Int,
    unchanged: Int
) {
  def total: Int = created + updated + unchanged

  def +(o: ImportResult): ImportResult =
    ImportResult(created + o.created, updated + o.updated, unchanged + o.unchanged)
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

  /** Whether this importer can go and get dossiers by itself. False for the
    * ones that wait to be handed data (CSV upload, JSON bulk POST): those have
    * nothing to re-read, so they are left out of the refresh button and of
    * `ImportService.refreshAll`.
    */
  def canPull: Boolean = false
}
