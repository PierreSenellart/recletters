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

  /** Insert-or-update keyed by (call_id, external_ref) when external_ref is
    * present, otherwise by (call_id, name). Idempotent; used by importers.
    * Returns (dossier id, isNew).
    */
  def upsert(
      callId: Int,
      name: String,
      externalRef: Option[String],
      url: Option[String],
      details: Option[String]
  ): (Long, Boolean) = db.withTransaction { implicit c =>
    val existingId: Option[Long] = externalRef match {
      case Some(ref) =>
        SQL"""SELECT id FROM dossier
              WHERE call_id=$callId AND external_ref=$ref"""
          .as(scalar[Long].singleOpt)
      case None =>
        SQL"""SELECT id FROM dossier
              WHERE call_id=$callId AND name=$name AND external_ref IS NULL"""
          .as(scalar[Long].singleOpt)
    }
    existingId match {
      case Some(id) =>
        SQL"""UPDATE dossier
              SET name=$name, url=$url, details=$details
              WHERE id=$id"""
          .executeUpdate()
        (id, false)
      case None =>
        val id =
          SQL"""INSERT INTO dossier (call_id, name, external_ref, url, details)
                VALUES ($callId, $name, $externalRef, $url, $details)"""
            .executeInsert(scalar[Long].single)
        (id, true)
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
)

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
  ): Boolean = db.withTransaction { implicit c =>
    val existing =
      SQL"""SELECT 1 FROM referee_request
            WHERE dossier=$dossierId AND email=$email"""
        .as(scalar[Int].singleOpt)
    if (existing.isDefined) {
      SQL"""UPDATE referee_request SET role=$role, details=$details
            WHERE dossier=$dossierId AND email=$email"""
        .executeUpdate()
      false
    } else {
      SQL"""INSERT INTO referee_request(dossier, email, details, role)
            VALUES ($dossierId, $email, $details, $role)"""
        .executeUpdate()
      true
    }
  }

  /** Mint a fresh token. Returns the plaintext (mailed in the request link);
    * the SHA-256 hash is what gets stored. Replaces any previous token.
    */
  def generateToken(r: RefereeRequest): String = {
    val token = PasswordHasher.newToken()
    val hash  = PasswordHasher.sha256(token)
    val expiresAt = java.sql.Timestamp.valueOf(
      java.time.LocalDateTime.now().plus(TokenTTL)
    )
    db.withTransaction { implicit c =>
      SQL"""DELETE FROM referee_token
            WHERE dossier=${r.dossier.id} AND email=${r.email}"""
        .executeUpdate()
      SQL"""INSERT INTO referee_token(dossier, email, token_hash, expires_at)
            VALUES (${r.dossier.id}, ${r.email}, $hash, $expiresAt)"""
        .executeUpdate()
      SQL"""UPDATE referee_request SET status='requested'
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
