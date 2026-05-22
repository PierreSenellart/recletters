package models

import javax.inject._

import play.api.db._
import anorm._
import anorm.SqlParser._

case class User(
    id: Long,
    email: String,
    last_name: Option[String],
    first_name: Option[String]
)

object UserService {
  def parser(prefix: String = "users"): RowParser[User] =
    Macro.namedParser[User](new PrefixNaming(prefix))

  // Reset-token TTL. Matches the wording in the password-reset email.
  val ResetTokenTTL: java.time.Duration = java.time.Duration.ofHours(48)
}

class UserService @Inject() (db: Database) {
  import UserService._

  def find(id: Long): Option[User] =
    db.withConnection { implicit c =>
      SQL"""SELECT id, email, last_name, first_name FROM users WHERE id=$id"""
        .as(parser().singleOpt)
    }

  def getId(email: String): Option[Long] =
    db.withConnection { implicit c =>
      SQL"""SELECT id FROM users WHERE LOWER(email)=LOWER($email)"""
        .as(long("id").singleOpt)
    }

  /** Verifies the password in Scala via bcrypt; no SQL crypto. */
  def authenticate(email: String, password: String): Option[User] = {
    val row = db.withConnection { implicit c =>
      SQL"""SELECT id, email, last_name, first_name, password
            FROM users WHERE LOWER(email)=LOWER($email)"""
        .as((parser() ~ get[Option[String]]("password")).singleOpt)
    }
    row.flatMap { case user ~ hashed =>
      hashed match {
        case Some(h) if PasswordHasher.verifyPassword(password, h) => Some(user)
        case _                                                     => None
      }
    }
  }

  /** Issues a one-shot reset token. Returns the plaintext (mailed to the user);
    * only its SHA-256 is stored.
    */
  def generateToken(id: Long): String = {
    val token = PasswordHasher.newToken()
    val hash  = PasswordHasher.sha256(token)
    db.withConnection { implicit c =>
      SQL"UPDATE users SET token_hash=$hash, token_issued=NOW() WHERE id=$id"
        .executeUpdate()
    }
    token
  }

  def removeToken(id: Long): Unit =
    db.withConnection { implicit c =>
      SQL"UPDATE users SET token_hash=NULL WHERE id=$id".executeUpdate()
    }

  /** Looks up by the SHA-256 of the supplied token; rejects tokens older than
    * the TTL. Constant-time compare not strictly needed here because we look
    * up by hash equality in SQL (the index makes timing dependent on the row
    * being present, not on byte-level equality), but we still verify the hash
    * matches in Scala to defend against very pathological hash collisions.
    */
  def getUserFromToken(token: String): Option[Long] = {
    val hash   = PasswordHasher.sha256(token)
    val cutoff = java.sql.Timestamp.valueOf(
      java.time.LocalDateTime.now().minus(ResetTokenTTL)
    )
    db.withConnection { implicit c =>
      val rows =
        SQL"""SELECT id, token_hash FROM users
              WHERE token_hash IS NOT NULL AND token_issued > $cutoff"""
          .as((long("id") ~ get[Array[Byte]]("token_hash")).*)
      rows.collectFirst {
        case id ~ stored if PasswordHasher.constantTimeEquals(stored, hash) =>
          id
      }
    }
  }

  def setPassword(id: Long, password: String): Unit = {
    val hashed = PasswordHasher.hashPassword(password)
    db.withConnection { implicit c =>
      SQL"UPDATE users SET password=$hashed WHERE id=$id".executeUpdate()
    }
  }

  /** Creates a committee user. Used by the admin CLI (tools.AddUser). Returns
    * the new user id.
    */
  def create(
      email: String,
      firstName: Option[String],
      lastName: Option[String],
      password: String
  ): Long = {
    val hashed = PasswordHasher.hashPassword(password)
    db.withConnection { implicit c =>
      SQL"""INSERT INTO users(email, first_name, last_name, password)
            VALUES ($email, $firstName, $lastName, $hashed)"""
        .executeInsert(scalar[Long].single)
    }
  }
}
