package services.imports

import javax.inject.{Inject, Singleton}
import play.api.Logger

import models.{Call, DossierService, ImportOutcome, RefereeRequestService}

@Singleton
class ImportService @Inject() (
    dossiers: DossierService,
    referees: RefereeRequestService,
    registry: ImportRegistry
) {

  private val logger = Logger(getClass)

  def listAvailable: Seq[DossierImporter] = registry.enabled

  /** Importers a refresh can re-read (the /requests button, and cron). */
  def listPullable: Seq[DossierImporter] = registry.pullable

  /** Re-read every upstream that can pull, for one call. This is the whole of
    * "refresh": importers are idempotent and now write only what differs, so
    * running this on a timer costs one query per upstream and touches nothing
    * unless the upstream moved.
    */
  def refreshAll(call: Call): ImportResult =
    registry.pullable.foldLeft(ImportResult.empty) { (acc, imp) =>
      acc + applyAll(call, imp.fetch(call))
    }

  def run(name: String, call: Call): Either[String, ImportResult] =
    registry.byName(name) match {
      case None                        => Left(s"Unknown importer: $name")
      case Some(imp) if !imp.isEnabled => Left(s"Importer disabled: $name")
      case Some(imp) =>
        Right(applyAll(call, imp.fetch(call)))
    }

  def applyAll(call: Call, items: Seq[ImportedDossier]): ImportResult =
    items.foldLeft(ImportResult.empty)((acc, d) => acc + applyOne(call, d))

  def applyOne(call: Call, d: ImportedDossier): ImportResult = {
    val (dossierId, dossierOutcome) = dossiers.upsert(
      callId      = call.id,
      name        = d.name,
      externalRef = d.externalRef,
      url         = d.url,
      details     = d.notes
    )
    val refereeOutcomes =
      d.referees.map(r => referees.addReferee(dossierId, r.email, r.role, r.notes))

    // A dossier counts as updated when its own metadata moved *or* when any of
    // its referee rows did, so that a role fixed upstream shows up in the run's
    // counts too.
    val touched = refereeOutcomes.exists(_ != ImportOutcome.unchanged)
    if (dossierOutcome == ImportOutcome.created) {
      logger.info(s"import: created dossier $dossierId (${describe(d)})")
      ImportResult(1, 0, 0)
    } else if (dossierOutcome == ImportOutcome.updated || touched) {
      logger.info(s"import: updated dossier $dossierId (${describe(d)})")
      ImportResult(0, 1, 0)
    } else ImportResult(0, 0, 1)
  }

  /** Identify a dossier in the log by its upstream key, falling back to name. */
  private def describe(d: ImportedDossier): String =
    d.externalRef.map("ref=" + _).getOrElse(d.name)
}
