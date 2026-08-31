package models

import anorm._
import anorm.SqlParser._
import java.time.{Duration, ZonedDateTime}
import play.api.db.Database
import javax.inject.Inject

/** request_status used to be a native PostgreSQL ENUM. It is now a plain
  * VARCHAR(16) constrained at the SQL level by a CHECK clause and at the Scala
  * level by this sealed set of values.
  */
object RequestStatus {
  type RequestStatus = String

  val news      : RequestStatus = "new"
  val requested : RequestStatus = "requested"
  val received  : RequestStatus = "received"
  val declined  : RequestStatus = "declined"
  val cancelled : RequestStatus = "cancelled"

  val All: Set[RequestStatus] =
    Set(news, requested, received, declined, cancelled)

  def parse(s: String): RequestStatus =
    if (All.contains(s)) s
    else throw new IllegalArgumentException(s"Unknown request_status: $s")
}

/** What an idempotent import write did to a row. `updated` means at least one
  * field really differed from what upstream now sends; `unchanged` means the
  * stored row already matched, so nothing was written. Keeping the two apart is
  * what lets a scheduled re-import run as often as it likes and still report
  * only genuine upstream drift.
  */
object ImportOutcome {
  type ImportOutcome = String

  val created   : ImportOutcome = "created"
  val updated   : ImportOutcome = "updated"
  val unchanged : ImportOutcome = "unchanged"
}

case class Dossier(
    id: Long,
    call_id: Int,
    name: String,
    external_ref: Option[String],
    details: Option[String],
    url: Option[String]
)

object DossierService {
  def parser(prefix: String = "dossier"): RowParser[Dossier] =
    Macro.namedParser[Dossier](new PrefixNaming(prefix))
}

class DossierService @Inject() (db: Database) {
  import DossierService._

  private val dossierMetadata =
    get[Long]("id") ~ get[String]("name") ~
      get[Option[String]]("url") ~ get[Option[String]]("details")

  /** Insert-or-update keyed by (call_id, external_ref) when external_ref is
    * present, otherwise by (call_id, name). Idempotent; used by importers.
    * Returns (dossier id, what the write actually did).
    *
    * A matching row whose name, url and details already equal what upstream
    * sends is left untouched and reported as `unchanged`: re-importing an
    * unchanged dossier writes nothing, so the counts a cron job sees are a
    * report of real upstream changes rather than of how many rows were seen.
    */
  def upsert(
      callId: Int,
      name: String,
      externalRef: Option[String],
      url: Option[String],
      details: Option[String]
  ): (Long, ImportOutcome.ImportOutcome) = db.withTransaction { implicit c =>
    val existing = externalRef match {
      case Some(ref) =>
        SQL"""SELECT id, name, url, details FROM dossier
              WHERE call_id=$callId AND external_ref=$ref"""
          .as(dossierMetadata.singleOpt)
      case None =>
        SQL"""SELECT id, name, url, details FROM dossier
              WHERE call_id=$callId AND name=$name AND external_ref IS NULL"""
          .as(dossierMetadata.singleOpt)
    }
    existing match {
      case Some(id ~ oldName ~ oldUrl ~ oldDetails) =>
        if (oldName == name && oldUrl == url && oldDetails == details)
          (id, ImportOutcome.unchanged)
        else {
          SQL"""UPDATE dossier
                SET name=$name, url=$url, details=$details
                WHERE id=$id"""
            .executeUpdate()
          (id, ImportOutcome.updated)
        }
      case None =>
        val id =
          SQL"""INSERT INTO dossier (call_id, name, external_ref, url, details)
                VALUES ($callId, $name, $externalRef, $url, $details)"""
            .executeInsert(scalar[Long].single)
        (id, ImportOutcome.created)
    }
  }
}

case class RefereeRequest(
    dossier: Dossier,
    email: String,
    details: Option[String],
    role: Option[String],
    name: Option[String],
    status: RequestStatus.RequestStatus,
    status_update: ZonedDateTime
) {

  /** Date shown in the requests table: the last meaningful event on the row —
    * when the request was sent (for 'requested', since generateToken now stamps
    * status_update on send) or when the referee acted (received/declined). None
    * for a 'new' row that was never sent.
    */
  def actionDate: Option[ZonedDateTime] =
    if (status == RequestStatus.news) None else Some(status_update)
}

object RefereeRequestService {
  implicit val dossierParser: RowParser[Dossier] = DossierService.parser()

  // Default referee-token lifetime. Override on a per-call basis later if needed.
  val TokenTTL: Duration = Duration.ofDays(180)

  def parser(prefix: String = "referee_request"): RowParser[RefereeRequest] =
    Macro.namedParser[RefereeRequest](new PrefixNaming(prefix))
}

class RefereeRequestService @Inject() (db: Database) {
  import RefereeRequestService._

  def findAll(
      callId: Int,
      status: Option[RequestStatus.RequestStatus] = None
  ): Seq[RefereeRequest] =
    db.withConnection { implicit c =>
      SQL("""SELECT referee_request.dossier      AS "referee_request.dossier",
                    referee_request.email        AS "referee_request.email",
                    referee_request.details      AS "referee_request.details",
                    referee_request.role         AS "referee_request.role",
                    referee_letter.name          AS "referee_request.name",
                    referee_request.status       AS "referee_request.status",
                    referee_request.status_update AS "referee_request.status_update",
                    dossier.id                   AS "dossier.id",
                    dossier.call_id              AS "dossier.call_id",
                    dossier.name                 AS "dossier.name",
                    dossier.external_ref         AS "dossier.external_ref",
                    dossier.details              AS "dossier.details",
                    dossier.url                  AS "dossier.url"
             FROM referee_request
             LEFT JOIN referee_letter
               ON referee_letter.dossier = referee_request.dossier
              AND referee_letter.email   = referee_request.email
             JOIN dossier ON dossier.id = referee_request.dossier
             WHERE dossier.call_id = {callId}
               AND ({status} IS NULL OR referee_request.status = {status})
             ORDER BY dossier.id, referee_request.status_update,
                      referee_request.details""")
        .on("callId" -> callId, "status" -> status)
        .as(parser().*)
    }

  def findByRefereeToken(token: String): Option[RefereeRequest] = {
    val hash = PasswordHasher.sha256(token)
    val candidates = db.withConnection { implicit c =>
      SQL"""SELECT referee_request.dossier      AS "referee_request.dossier",
                   referee_request.email        AS "referee_request.email",
                   referee_request.details      AS "referee_request.details",
                   referee_request.role         AS "referee_request.role",
                   referee_letter.name          AS "referee_request.name",
                   referee_request.status       AS "referee_request.status",
                   referee_request.status_update AS "referee_request.status_update",
                   dossier.id                   AS "dossier.id",
                   dossier.call_id              AS "dossier.call_id",
                   dossier.name                 AS "dossier.name",
                   dossier.external_ref         AS "dossier.external_ref",
                   dossier.details              AS "dossier.details",
                   dossier.url                  AS "dossier.url",
                   referee_token.token_hash     AS token_hash
            FROM referee_request
            JOIN referee_token
              ON referee_token.dossier = referee_request.dossier
             AND referee_token.email   = referee_request.email
            JOIN dossier ON dossier.id = referee_request.dossier
            LEFT JOIN referee_letter
              ON referee_letter.dossier = referee_request.dossier
             AND referee_letter.email   = referee_request.email
            WHERE referee_token.expires_at > NOW()"""
        .as((parser() ~ get[Array[Byte]]("token_hash")).*)
    }
    candidates.collectFirst {
      case r ~ stored if PasswordHasher.constantTimeEquals(stored, hash) => r
    }
  }

  def add(r: RefereeRequest): Long =
    db.withTransaction { implicit c =>
      val id =
        SQL"""INSERT INTO dossier(call_id, name, details, url)
              VALUES (${r.dossier.call_id}, ${r.dossier.name},
                      ${r.dossier.details}, ${r.dossier.url})"""
          .executeInsert(scalar[Long].single)
      SQL"""INSERT INTO referee_request(dossier, email, details, role)
            VALUES ($id, ${r.email}, ${r.details}, ${r.role})"""
        .executeUpdate()
      id
    }

  /** Adds a referee row for an already-existing dossier. Used by importers. */
  def addReferee(
      dossierId: Long,
      email: String,
      role: Option[String],
      details: Option[String]
  ): ImportOutcome.ImportOutcome = db.withTransaction { implicit c =>
    val existing =
      SQL"""SELECT role, details FROM referee_request
            WHERE dossier=$dossierId AND email=$email"""
        .as((get[Option[String]]("role") ~ get[Option[String]]("details")).singleOpt)
    existing match {
      case Some(oldRole ~ oldDetails) =>
        // Same caveat as the dossier upsert: only write when something moved,
        // so an unchanged referee never inflates the "updated" count. status
        // and tokens are never touched here.
        if (oldRole == role && oldDetails == details) ImportOutcome.unchanged
        else {
          SQL"""UPDATE referee_request SET role=$role, details=$details
                WHERE dossier=$dossierId AND email=$email"""
            .executeUpdate()
          ImportOutcome.updated
        }
      case None =>
        SQL"""INSERT INTO referee_request(dossier, email, details, role)
              VALUES ($dossierId, $email, $details, $role)"""
          .executeUpdate()
        ImportOutcome.created
    }
  }

  /** Mint a fresh token. Returns the plaintext (mailed in the request link);
    * the SHA-256 hash is what gets stored. Earlier tokens are left in place —
    * a reminder adds a new one without invalidating the link in any request
    * email the referee already received (each expires on its own). Only the
    * hash is stored, so a DB leak still exposes no usable token.
    */
  def generateToken(r: RefereeRequest): String = {
    val token = PasswordHasher.newToken()
    val hash  = PasswordHasher.sha256(token)
    val expiresAt = java.sql.Timestamp.valueOf(
      java.time.LocalDateTime.now().plus(TokenTTL)
    )
    db.withTransaction { implicit c =>
      SQL"""INSERT INTO referee_token(dossier, email, token_hash, expires_at)
            VALUES (${r.dossier.id}, ${r.email}, $hash, $expiresAt)"""
        .executeUpdate()
      // Stamp status_update on send so it holds the (most recent) sent date for
      // a 'requested' row — not the row's import time. This makes status_update
      // the single meaningful timestamp across all statuses.
      SQL"""UPDATE referee_request SET status='requested', status_update=NOW()
            WHERE dossier=${r.dossier.id} AND email=${r.email}"""
        .executeUpdate()
    }
    token
  }

  def updateStatusTime(r: RefereeRequest): Unit =
    db.withConnection { implicit c =>
      SQL"""UPDATE referee_request SET status_update=NOW()
            WHERE dossier=${r.dossier.id} AND email=${r.email}"""
        .executeUpdate()
    }

  def receiveLetter(r: RefereeRequest, name: String, letter: Array[Byte]): Unit =
    db.withTransaction { implicit c =>
      SQL"""DELETE FROM referee_letter
            WHERE dossier=${r.dossier.id} AND email=${r.email}"""
        .executeUpdate()
      SQL"""INSERT INTO referee_letter(dossier, email, name, letter)
            VALUES (${r.dossier.id}, ${r.email}, $name, $letter)"""
        .executeUpdate()
      SQL"""UPDATE referee_request SET status='received', status_update=NOW()
            WHERE dossier=${r.dossier.id} AND email=${r.email}"""
        .executeUpdate()
    }

  def setDeclined(r: RefereeRequest): Unit =
    db.withTransaction { implicit c =>
      SQL"""DELETE FROM referee_letter
            WHERE dossier=${r.dossier.id} AND email=${r.email}"""
        .executeUpdate()
      SQL"""UPDATE referee_request SET status='declined', status_update=NOW()
            WHERE dossier=${r.dossier.id} AND email=${r.email}"""
        .executeUpdate()
    }

  def getLetter(id: Long, email: String): Option[Array[Byte]] =
    db.withConnection { implicit c =>
      SQL"""SELECT letter FROM referee_letter
            WHERE dossier=$id AND email=$email"""
        .as(scalar[Array[Byte]].singleOpt)
    }
}
