package services.imports

import java.nio.charset.StandardCharsets.UTF_8
import javax.inject.{Inject, Singleton}
import play.api.Configuration
import play.api.db.DBApi

import anorm._
import anorm.SqlParser._

import models.Call

/** HotCRP importer. Reads from a sibling MySQL/MariaDB instance whose JDBC URL
  * lives under `importers.hotcrp.db.*`. The mapping from HotCRP option ids to
  * referee roles is config-driven (`importers.hotcrp.option-mapping`).
  *
  * Currently restricts to papers whose decision is positive. Adjust the SQL if
  * your committee semantics differ.
  *
  * The shipped wiring registers this importer with `enabled=false` by default;
  * sites with co-located recletters + HotCRP toggle it on in secrets.conf.
  */
@Singleton
class HotCRPImporter @Inject() (
    config: Configuration,
    dbApi:  DBApi
) extends DossierImporter {

  val name                = "hotcrp"
  def isEnabled: Boolean  =
    config.getOptional[Boolean]("importers.hotcrp.enabled").getOrElse(false)
  override val canPull    = true

  private def optionMapping: Map[Int, String] =
    config
      .getOptional[Configuration]("importers.hotcrp.option-mapping")
      .map(_.entrySet.collect {
        case (k, v) if k.forall(_.isDigit) => k.toInt -> v.unwrapped().toString
      }.toMap)
      .getOrElse(Map.empty)

  /** Optional link back to the HotCRP paper, shown on the dossier name in the
    * requests list. A template with an `{id}` placeholder, e.g.
    * "https://host/prix/paper/{id}"; unset leaves the dossier link-less. */
  private def paperUrlTemplate: Option[String] =
    config.getOptional[String]("importers.hotcrp.paper-url").map(_.trim).filter(_.nonEmpty)

  def fetch(call: Call): Seq[ImportedDossier] = {
    if (!isEnabled) return Seq.empty
    val mapping = optionMapping
    val urlTmpl = paperUrlTemplate
    val db      = dbApi.database("hotcrp")

    case class PaperRow(paperId: Int, title: String, authors: String)
    case class OptionRow(paperId: Int, optionId: Int, email: String)

    // HotCRP stores its text columns as VARBINARY (title, authorInformation,
    // PaperOption.data), so the JDBC driver hands them back as byte[]. Read
    // them as Array[Byte] and decode UTF-8 rather than str(...), which would
    // throw TypeDoesNotMatch on the raw bytes.
    val papers = db.withConnection { implicit c =>
      SQL"""SELECT paperId, title, authorInformation
            FROM Paper
            WHERE timeWithdrawn = 0 AND timeSubmitted > 0"""
        .as((int("paperId") ~ byteArray("title").? ~ byteArray("authorInformation").?).map {
          case p ~ t ~ a => PaperRow(p, utf8(t), utf8(a))
        }.*)
    }

    val refs = db.withConnection { implicit c =>
      SQL"""SELECT paperId, optionId, data
            FROM PaperOption
            WHERE optionId IN (${mapping.keys.toSeq})"""
        .as((int("paperId") ~ int("optionId") ~ byteArray("data").?).map {
          case p ~ o ~ d => OptionRow(p, o, utf8(d))
        }.*)
    }

    val refByPaper: Map[Int, Seq[OptionRow]] = refs.groupBy(_.paperId)
    papers.map { p =>
      ImportedDossier(
        externalRef = Some(p.paperId.toString),
        name        = primaryAuthorName(p.authors),
        url         = urlTmpl.map(_.replace("{id}", p.paperId.toString)),
        notes       = Some(p.title),
        referees = refByPaper.getOrElse(p.paperId, Nil).flatMap { r =>
          // HotCRP text options hold arbitrary text; a candidate can type a
          // non-email into a referee field. Trim and keep only values that look
          // like an address, so we never create a broken referee request.
          val email = r.email.trim
          if (email.contains("@"))
            Some(ImportedReferee(email, mapping.get(r.optionId), None))
          else None
        }
      )
    }
  }

  /** Decode a nullable VARBINARY column to a UTF-8 string (NULL → ""). */
  private def utf8(bytes: Option[Array[Byte]]): String =
    bytes.map(new String(_, UTF_8)).getOrElse("")

  /** HotCRP stores authorInformation as a tab-delimited blob:
    * `first\tlast\temail\taffiliation\n` per author. We use the first author.
    */
  private def primaryAuthorName(blob: String): String = {
    val firstLine = blob.linesIterator.toSeq.headOption.getOrElse("")
    val parts     = firstLine.split('\t')
    val first     = parts.lift(0).getOrElse("").trim
    val last      = parts.lift(1).getOrElse("").trim
    Seq(first, last).filter(_.nonEmpty).mkString(" ")
  }
}
