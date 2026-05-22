package helpers

import java.sql.Timestamp
import java.time.LocalDateTime
import org.scalatest.{BeforeAndAfterEach, Suite}
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Configuration
import play.api.db.Database
import anorm._
import anorm.SqlParser._
import models.PasswordHasher

/** Resets the test database to a known state before each test: TRUNCATE all
  * app tables, RESTART IDENTITY, then re-insert a small fixture set.
  * Compatible with both PostgreSQL and MySQL.
  */
trait DBFixtures extends BeforeAndAfterEach { self: Suite & GuiceOneAppPerSuite =>

  /** %PDF-1.0 magic-byte prefix so the RefereeController.submit upload check
    * passes.
    */
  val minimalPdf: Array[Byte] =
    "%PDF-1.0\n1 0 obj<</Type /Catalog>>endobj\n%%EOF\n".getBytes("UTF-8")

  def db: Database = app.injector.instanceOf[Database]

  private def configuration: Configuration =
    app.injector.instanceOf[Configuration]

  private def isPostgres: Boolean =
    configuration.get[String]("db.default.url").toLowerCase.contains("postgres")

  /** Look up an inserted call's id by slug. */
  def callId(slug: String): Int =
    db.withConnection { implicit c =>
      SQL"SELECT id FROM call_ WHERE slug = $slug".as(scalar[Int].single)
    }

  /** Look up an inserted dossier's id by name (within the only test call). */
  def dossierId(name: String): Long =
    db.withConnection { implicit c =>
      SQL"SELECT id FROM dossier WHERE name = $name".as(scalar[Long].single)
    }

  /** Take a peek at a referee_request status. */
  def refereeStatus(dossier: Long, email: String): String =
    db.withConnection { implicit c =>
      SQL"""SELECT status FROM referee_request
            WHERE dossier=$dossier AND email=$email"""
        .as(scalar[String].single)
    }

  /** Issue a fresh referee token for a given (dossier, email) pair. Returns
    * the plaintext so tests can use it in URLs. Only the hash is stored.
    */
  def issueToken(dossier: Long, email: String, ttlDays: Int = 30): String = {
    val token  = PasswordHasher.newToken()
    val hash   = PasswordHasher.sha256(token)
    val expiry = Timestamp.valueOf(LocalDateTime.now.plusDays(ttlDays.toLong))
    db.withConnection { implicit c =>
      SQL"""DELETE FROM referee_token WHERE dossier=$dossier AND email=$email"""
        .executeUpdate()
      SQL"""INSERT INTO referee_token(dossier, email, token_hash, expires_at)
            VALUES ($dossier, $email, $hash, $expiry)"""
        .executeUpdate()
      SQL"""UPDATE referee_request SET status='requested'
            WHERE dossier=$dossier AND email=$email"""
        .executeUpdate()
    }
    token
  }

  override def beforeEach(): Unit = {
    db.withConnection { implicit c =>
      // PostgreSQL: one TRUNCATE with CASCADE + RESTART IDENTITY is cleanest.
      // MySQL: needs disabling FK checks and DELETE then auto-increment reset.
      if (isPostgres) {
        SQL"""TRUNCATE referee_letter, referee_token, referee_request, dossier,
                       call_, users
              RESTART IDENTITY CASCADE""".executeUpdate()
      } else {
        SQL"SET FOREIGN_KEY_CHECKS=0".executeUpdate()
        for (t <- Seq(
                 "referee_letter", "referee_token", "referee_request",
                 "dossier", "call_", "users"
               )) {
          SQL("DELETE FROM " + t).executeUpdate()
          SQL("ALTER TABLE " + t + " AUTO_INCREMENT = 1").executeUpdate()
        }
        SQL"SET FOREIGN_KEY_CHECKS=1".executeUpdate()
      }
    }

    val adminPw = PasswordHasher.hashPassword("admin")
    val deadline = Timestamp.valueOf(LocalDateTime.now.plusDays(30))

    db.withConnection { implicit c =>
      SQL"""INSERT INTO users(email, first_name, last_name, password)
            VALUES ('admin@test.local', 'Admin', 'Test', $adminPw)"""
        .executeInsert()
      SQL"""INSERT INTO call_(slug, label, deadline)
            VALUES ('test-call', 'Test Call', $deadline)"""
        .executeInsert()
    }

    val cid = callId("test-call")
    db.withConnection { implicit c =>
      // Two dossiers; one with two referees, one with one.
      SQL"""INSERT INTO dossier(call_id, name, external_ref, url, details)
            VALUES ($cid, 'Alice Example', '42', 'https://x/42', null)"""
        .executeInsert()
      SQL"""INSERT INTO dossier(call_id, name, external_ref, url, details)
            VALUES ($cid, 'Bob Example', null, null, null)"""
        .executeInsert()
    }

    val aliceId = dossierId("Alice Example")
    val bobId   = dossierId("Bob Example")

    db.withConnection { implicit c =>
      SQL"""INSERT INTO referee_request(dossier, email, role)
            VALUES ($aliceId, 'alice.ref1@test.local', 'supervisor')"""
        .executeUpdate()
      SQL"""INSERT INTO referee_request(dossier, email, role)
            VALUES ($aliceId, 'alice.ref2@test.local', 'external')"""
        .executeUpdate()
      SQL"""INSERT INTO referee_request(dossier, email)
            VALUES ($bobId, 'bob.ref@test.local')"""
        .executeUpdate()
    }

    super.beforeEach()
  }
}
