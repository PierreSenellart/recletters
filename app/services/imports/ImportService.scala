package services.imports

import javax.inject.{Inject, Singleton}
import models.{Call, DossierService, RefereeRequestService}

@Singleton
class ImportService @Inject() (
    dossiers: DossierService,
    referees: RefereeRequestService,
    registry: ImportRegistry
) {

  def listAvailable: Seq[DossierImporter] = registry.enabled

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
    val (dossierId, isNew) = dossiers.upsert(
      callId      = call.id,
      name        = d.name,
      externalRef = d.externalRef,
      url         = d.url,
      details     = d.notes
    )
    for (r <- d.referees) {
      referees.addReferee(dossierId, r.email, r.role, r.notes)
    }
    if (isNew) ImportResult(1, 0, 0) else ImportResult(0, 1, 0)
  }
}
