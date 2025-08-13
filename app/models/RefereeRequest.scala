package models

import anorm._
import anorm.SqlParser._
import java.time.ZonedDateTime
import play.api.db.Database
import javax.inject.Inject

object RequestStatus extends DBEnum {
  type RequestStatus = Value

  val news = Value("new")
  val requested = Value("requested")
  var received = Value("received")
  val declined = Value("declined")
  val cancelled = Value("cancelled")

  implicit val columnToRequestStatus: Column[RequestStatus] =
    enumToType[RequestStatus](RequestStatus.withName)
}

case class Dossier(
  id: Long,
  year: Int,
  name: String,
  details: Option[String],
  url: Option[String]
)

object DossierService {
  def parser(prefix: String = "dossier"): RowParser[Dossier] =
    Macro.namedParser[Dossier](new PrefixNaming(prefix))
}

case class RefereeRequest(
    dossier: Dossier,
    email: String,
    details: Option[String],
    name: Option[String],
    status: RequestStatus.RequestStatus,
    status_update: ZonedDateTime
)

object RefereeRequestService {
  implicit val dossierParser: RowParser[Dossier] = DossierService.parser()

  def parser(prefix: String = "referee_request"): RowParser[RefereeRequest] =
    Macro.namedParser[RefereeRequest](new PrefixNaming(prefix))
}

class RefereeRequestService @Inject() (db: Database) {
  import RefereeRequestService._

  def findAll(year: Int, status: Option[RequestStatus.RequestStatus] = None):
    Seq[RefereeRequest] = {
    db.withConnection { implicit connection =>
      SQL("""SELECT referee_request.*, dossier.*, referee_letter.name AS "referee_request.name"
             FROM referee_request
             NATURAL LEFT JOIN referee_letter
             JOIN dossier ON referee_request.dossier=id
             WHERE year={year} AND ({status} IS NULL OR status={status}::request_status)
             ORDER BY dossier, status_update, referee_request.details""")
               .on("year" -> year, "status" -> status.map(_.toString))
               .as(parser().*)
    }
  }

  def findByRefereeToken(token: String) : Option[RefereeRequest] = {
    db.withConnection { implicit connection =>
      SQL"""SELECT referee_request.*, dossier.*, referee_letter.name AS "referee_request.name"
            FROM referee_request
            NATURAL LEFT JOIN referee_letter
            JOIN dossier ON referee_request.dossier=id, referee_token
            WHERE (referee_request.dossier,referee_request.email) =
                  (referee_token.dossier,referee_token.email) AND
                  token=$token""".as(parser().singleOpt)
    }
  }

  def add(r: RefereeRequest) : Long = {
    db.withTransaction { implicit connection =>
      val id = SQL"""INSERT INTO dossier(
        year, name, details, url)
        VALUES(${r.dossier.year}, ${r.dossier.name}, ${r.dossier.details},
          ${r.dossier.url})"""
        .executeInsert()
        .get
      SQL"""INSERT INTO referee_request(dossier,email,details)
            VALUES($id, ${r.email}, ${r.details})"""
        .executeUpdate()

      id
    }
  }

  def generateToken(r: RefereeRequest): String = {
    db.withTransaction { implicit connection =>
      val token =
        SQL"SELECT replace(replace(encode(gen_random_bytes(15),'base64'),'/','_'),'+','-') AS token"
          .as(get[String]("token").single)

      SQL"DELETE FROM referee_token WHERE dossier=${r.dossier.id} AND email=${r.email}"
        .executeUpdate()

      SQL"""INSERT INTO referee_token(dossier, email, token)
            VALUES(${r.dossier.id},${r.email},$token)"""
        .executeUpdate()

      SQL"""UPDATE referee_request SET status='requested'
            WHERE dossier=${r.dossier.id} AND email=${r.email}"""
        .executeUpdate()

      token
    }
  }

  def receiveLetter(r: RefereeRequest, name: String, letter: Array[Byte]) = {
    db.withTransaction { implicit connection =>
      SQL"DELETE FROM referee_letter WHERE dossier=${r.dossier.id} AND email=${r.email}"
        .executeUpdate()

      SQL"""INSERT INTO referee_letter(dossier, email, name, letter)
            VALUES(${r.dossier.id}, ${r.email}, ${name}, ${letter})"""
        .executeUpdate()

      SQL"""UPDATE referee_request SET status='received'
            WHERE dossier=${r.dossier.id} AND email=${r.email}"""
        .executeUpdate()
    }
  }

  def setDeclined(r: RefereeRequest) = {
    db.withTransaction { implicit connection =>
      SQL"DELETE FROM referee_letter WHERE dossier=${r.dossier.id} AND email=${r.email}"
        .executeUpdate()

      SQL"""UPDATE referee_request SET status='declined'
            WHERE dossier=${r.dossier.id} AND email=${r.email}"""
        .executeUpdate()
    }
  }

  def getLetter(id: Long, email: String): Option[Array[Byte]] = {
    db.withConnection { implicit connection =>
      SQL"SELECT letter FROM referee_letter WHERE dossier=$id AND email=$email".as(
        scalar[Array[Byte]].singleOpt
      )
    }
  }
}
