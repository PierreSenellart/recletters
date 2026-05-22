package services.imports

import javax.inject.{Inject, Singleton}
import play.api.Configuration
import play.api.libs.json._

import models.Call

/** Pull-mode placeholder importer; the active path is the route handler
  * `ImportController.apiBulk` which posts to `ImportService.applyAll` directly.
  *
  * Listed in the registry so the `/import` UI shows the JSON-API as a
  * documented integration point even though it does not pull on its own.
  */
@Singleton
class JsonApiImporter @Inject() (config: Configuration) extends DossierImporter {
  val name               = "json-api"
  def isEnabled: Boolean =
    config.getOptional[Boolean]("importers.json.enabled").getOrElse(true)
  def fetch(call: Call): Seq[ImportedDossier] = Seq.empty
}

object JsonApiImporter {
  // JSON codecs for the bulk-ingest endpoint. Only Reads are needed; the
  // server never serialises an ImportedDossier back out.
  implicit val refereeReads: Reads[ImportedReferee] = Json.reads[ImportedReferee]
  implicit val dossierReads: Reads[ImportedDossier] = Json.reads[ImportedDossier]
}
