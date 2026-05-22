package services.imports

import java.io.{InputStream, InputStreamReader}
import javax.inject.{Inject, Singleton}
import play.api.Configuration
import com.github.tototoshi.csv.CSVReader

import models.Call

/** CSV importer. Unlike HotCRP and JSON API, this one has no upstream: it
  * accepts an upload from the committee UI and the controller calls
  * `parseStream` directly.
  *
  * CSV columns (order does not matter; the header row drives the mapping):
  *   external_ref, name, url, notes, referee_email, referee_role
  *
  * One row per (dossier, referee) pair. Rows sharing the same external_ref (or
  * same name when external_ref is empty) are merged into a single dossier with
  * multiple referees.
  */
@Singleton
class CsvImporter @Inject() (config: Configuration) extends DossierImporter {
  val name                = "csv"
  def isEnabled: Boolean  =
    config.getOptional[Boolean]("importers.csv.enabled").getOrElse(true)

  /** CSV is upload-driven; there is no `fetch(call)`. */
  def fetch(call: Call): Seq[ImportedDossier] = Seq.empty

  def parseStream(is: InputStream): Seq[ImportedDossier] = {
    val reader = CSVReader.open(new InputStreamReader(is, "UTF-8"))
    try {
      val rows = reader.allWithHeaders()
      val grouped = rows.groupBy { row =>
        row.get("external_ref").filter(_.nonEmpty)
          .map(Left(_): Either[String, String])
          .getOrElse(Right(row.getOrElse("name", "")))
      }
      grouped.values.toSeq.flatMap { rowsForDossier =>
        rowsForDossier.headOption.map { head =>
          ImportedDossier(
            externalRef = head.get("external_ref").filter(_.nonEmpty),
            name        = head.getOrElse("name", "").trim,
            url         = head.get("url").filter(_.nonEmpty),
            notes       = head.get("notes").filter(_.nonEmpty),
            referees = rowsForDossier.flatMap { r =>
              r.get("referee_email").filter(_.nonEmpty).map { e =>
                ImportedReferee(
                  email = e.trim,
                  role  = r.get("referee_role").filter(_.nonEmpty),
                  notes = None
                )
              }
            }
          )
        }
      }
    } finally reader.close()
  }
}
