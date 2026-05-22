package services.imports

import javax.inject.{Inject, Singleton}

/** All shipped importers are listed here. Adding a new one means: write a
  * subclass of `DossierImporter`, inject it into the registry, and add a
  * configuration key under `importers.*` so operators can enable it.
  */
@Singleton
class ImportRegistry @Inject() (
    csv:  CsvImporter,
    json: JsonApiImporter,
    hot:  HotCRPImporter
) {
  val all: Seq[DossierImporter] = Seq(csv, json, hot)

  def enabled: Seq[DossierImporter] = all.filter(_.isEnabled)

  def byName(n: String): Option[DossierImporter] = all.find(_.name == n)
}
