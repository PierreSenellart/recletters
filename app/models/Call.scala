package models

import anorm._
import anorm.SqlParser._
import java.time.{Duration, ZonedDateTime}
import javax.inject.Inject
import play.api.db.Database

/** A "call" is the unit of work: an award round, a hiring committee, a grant
  * call, a student admissions cycle, etc. Calls have explicit open/deadline
  * timestamps so multiple parallel and one-off calls coexist.
  *
  * Stored grace period is `grace_seconds INTEGER` so the column type is
  * portable across PostgreSQL and MySQL/MariaDB (avoids the PG-only INTERVAL).
  */
case class Call(
    id: Int,
    slug: String,
    label: String,
    opens_at: Option[ZonedDateTime],
    deadline: ZonedDateTime,
    grace_seconds: Int,
    site_name_override: Option[String],
    email_from_override: Option[String],
    is_archived: Boolean
) {
  def effectiveDeadline: ZonedDateTime =
    deadline.plus(Duration.ofSeconds(grace_seconds.toLong))

  def isOpenAt(now: ZonedDateTime): Boolean =
    opens_at.forall(!_.isAfter(now)) && now.isBefore(effectiveDeadline)
}

object Call {
  /** Default parser used when querying the `call_` table directly. */
  def parser: RowParser[Call] = Macro.namedParser[Call]
}

class CallService @Inject() (db: Database) {

  /** All non-archived calls, plus any archived ones still referenced by a
    * dossier. Used to populate the call selector in the committee UI.
    */
  def listVisible(): Seq[Call] =
    db.withConnection { implicit c =>
      SQL"""SELECT id, slug, label, opens_at, deadline, grace_seconds,
                   site_name_override, email_from_override, is_archived
            FROM call_
            ORDER BY deadline DESC"""
        .as(Call.parser.*)
    }

  def find(id: Int): Option[Call] =
    db.withConnection { implicit c =>
      SQL"""SELECT id, slug, label, opens_at, deadline, grace_seconds,
                   site_name_override, email_from_override, is_archived
            FROM call_ WHERE id=$id"""
        .as(Call.parser.singleOpt)
    }

  def findBySlug(slug: String): Option[Call] =
    db.withConnection { implicit c =>
      SQL"""SELECT id, slug, label, opens_at, deadline, grace_seconds,
                   site_name_override, email_from_override, is_archived
            FROM call_ WHERE slug=$slug"""
        .as(Call.parser.singleOpt)
    }

  /** The "active" call is the most-recently-opening non-archived call whose
    * effective deadline (deadline + grace) is still in the future. Falls back
    * to the most recent call if none are currently open.
    */
  def activeCall(): Option[Call] = {
    val calls = listVisible().filterNot(_.is_archived)
    val now   = ZonedDateTime.now()
    calls.find(_.isOpenAt(now)).orElse(calls.headOption)
  }

  def create(
      slug: String,
      label: String,
      deadline: java.sql.Timestamp,
      opensAt: Option[java.sql.Timestamp] = None,
      graceSeconds: Int = 0
  ): Int =
    db.withConnection { implicit c =>
      SQL"""INSERT INTO call_(slug, label, opens_at, deadline, grace_seconds)
            VALUES ($slug, $label, $opensAt, $deadline, $graceSeconds)"""
        .executeInsert(scalar[Int].single)
    }
}
