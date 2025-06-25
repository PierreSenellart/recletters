package models

import java.util.Date
import javax.inject._

import play.api.db._
import play.api.libs.json._
import anorm._
import anorm.SqlParser._

import scala.util.{Failure, Success => TrySuccess, Try}

import scala.language.postfixOps

case class User(
    id: Long,
    email: String,
    last_name: Option[String],
    first_name: Option[String],
)

object User {
  implicit val userWrites: Writes[User] = new Writes[User] {
    def writes(user: User) = Json.obj(
      "id" -> user.id,
      "last_name" -> user.last_name,
      "first_name" -> user.first_name,
    )
  }
}

object UserService {
  def parser(prefix: String = "users"): RowParser[User] =
    Macro.namedParser[User](new PrefixNaming(prefix))
}

class UserService @Inject() (db: Database) {
  import UserService._

  def find(id: Long): Option[User] = {
    db.withConnection { implicit connection =>
      SQL"""SELECT * FROM users WHERE id=$id""".as(parser().singleOpt)
    }
  }

  def getId(email: String): Option[Long] = {
    db.withConnection { implicit connection =>
      SQL"""SELECT id FROM users WHERE LOWER(email)=LOWER($email)""".as(
        long("id").singleOpt
      )
    }
  }

  def authenticate(email: String, password: String): Option[User] = {
    db.withConnection { implicit connection =>
      SQL"""SELECT * FROM users
            WHERE LOWER(email)=LOWER($email)
            AND password=crypt($password,password)""".as(parser().singleOpt)
    }
  }

  def generateToken(id: Long): String = {
    db.withConnection { implicit connection =>
      val token =
        SQL"SELECT replace(replace(encode(gen_random_bytes(15),'base64'),'/','_'),'+','-') AS token"
          .as(get[String]("token").single)

      SQL"UPDATE users SET token = digest($token, 'sha256'), token_issued = NOW() WHERE id=$id"
        .executeUpdate()

      token
    }
  }

  def removeToken(id: Long) = {
    db.withConnection { implicit connection =>
      SQL"UPDATE users SET token = NULL WHERE id=$id".executeUpdate()
    }
  }

  def getUserFromToken(token: String): Option[Long] = {
    db.withConnection { implicit connection =>
      SQL"SELECT id FROM users WHERE token = digest($token, 'sha256')::text AND token_issued > NOW() - interval '48 hours'"
        .as(long("id").singleOpt)
    }
  }

  def setPassword(id: Long, password: String) = {
    db.withConnection { implicit connection =>
      SQL"UPDATE users SET password=crypt($password,gen_salt('bf')) WHERE id=$id"
        .executeUpdate()
    }
  }
}
