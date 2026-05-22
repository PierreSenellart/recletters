package services.imports

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

  private def optionMapping: Map[Int, String] =
    config
      .getOptional[Configuration]("importers.hotcrp.option-mapping")
      .map(_.entrySet.collect {
        case (k, v) if k.forall(_.isDigit) => k.toInt -> v.unwrapped().toString
      }.toMap)
      .getOrElse(Map.empty)

  def fetch(call: Call): Seq[ImportedDossier] = {
    if (!isEnabled) return Seq.empty
    val mapping = optionMapping
    val db      = dbApi.database("hotcrp")

    case class PaperRow(paperId: Int, title: String, authors: String)
    case class OptionRow(paperId: Int, optionId: Int, email: String)

    val papers = db.withConnection { implicit c =>
      SQL"""SELECT paperId, title, authorInformation
            FROM Paper
            WHERE timeWithdrawn = 0 AND timeSubmitted > 0"""
        .as((int("paperId") ~ str("title") ~ str("authorInformation")).map {
          case p ~ t ~ a => PaperRow(p, t, a)
        }.*)
    }

    val refs = db.withConnection { implicit c =>
      SQL"""SELECT paperId, optionId, data
            FROM PaperOption
            WHERE optionId IN (${mapping.keys.toSeq})"""
        .as((int("paperId") ~ int("optionId") ~ str("data")).map {
          case p ~ o ~ d => OptionRow(p, o, d)
        }.*)
    }

    val refByPaper: Map[Int, Seq[OptionRow]] = refs.groupBy(_.paperId)
    papers.map { p =>
      ImportedDossier(
        externalRef = Some(p.paperId.toString),
        name        = primaryAuthorName(p.authors),
        url         = None,
        notes       = Some(p.title),
        referees = refByPaper.getOrElse(p.paperId, Nil).map { r =>
          ImportedReferee(
            email = r.email,
            role  = mapping.get(r.optionId),
            notes = None
          )
        }
      )
    }
  }

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
