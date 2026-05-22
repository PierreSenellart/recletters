package services.imports

import java.time.ZonedDateTime
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatestplus.play._
import org.scalatestplus.play.guice._
import play.api.Application
import play.api.Configuration
import play.api.db.{DBApi, Database}
import play.api.inject.guice.GuiceApplicationBuilder
import anorm._

import models.Call

/** Exercises the HotCRPImporter against a real HotCRP-shaped MySQL database
  * (`hotcrp_test`). Skips cleanly when the test profile is PostgreSQL — there
  * is no HotCRP-on-PostgreSQL deployment to test against.
  *
  * Test fixtures live entirely in this spec; the hotcrp_test database is
  * created empty by test/create_test_db.sh and torn down each suite run by
  * `beforeAll`.
  */
class HotCRPImporterSpec extends PlaySpec with GuiceOneAppPerSuite with BeforeAndAfterAll {

  override def fakeApplication(): Application = {
    val resource = sys.props.getOrElse("config.resource", "test.conf")
    GuiceApplicationBuilder()
      .configure(Configuration(ConfigFactory.load(resource)))
      .build()
  }

  private def hotcrpEnabled: Boolean =
    app.injector.instanceOf[Configuration]
      .getOptional[Boolean]("importers.hotcrp.enabled")
      .getOrElse(false)

  private def hotcrp: Database =
    app.injector.instanceOf[DBApi].database("hotcrp")

  private def callFixture(id: Int = 1, slug: String = "test", label: String = "Test Call"): Call =
    Call(
      id            = id,
      slug          = slug,
      label         = label,
      opens_at      = None,
      deadline      = ZonedDateTime.now().plusDays(30),
      grace_seconds = 0,
      site_name_override  = None,
      email_from_override = None,
      is_archived   = false
    )

  override def beforeAll(): Unit = {
    if (!hotcrpEnabled) return
    hotcrp.withConnection { implicit c =>
      SQL"DROP TABLE IF EXISTS PaperOption".executeUpdate()
      SQL"DROP TABLE IF EXISTS Paper".executeUpdate()
      SQL"""CREATE TABLE Paper (
              paperId           INT          NOT NULL PRIMARY KEY,
              title             VARCHAR(255) NOT NULL,
              authorInformation TEXT         NOT NULL,
              timeWithdrawn     INT          NOT NULL DEFAULT 0,
              timeSubmitted     INT          NOT NULL DEFAULT 0
            )""".executeUpdate()
      SQL"""CREATE TABLE PaperOption (
              paperId   INT          NOT NULL,
              optionId  INT          NOT NULL,
              data      VARCHAR(255) NOT NULL,
              PRIMARY KEY (paperId, optionId, data)
            )""".executeUpdate()

      // Submitted, not withdrawn — the only one that should be returned.
      SQL"""INSERT INTO Paper(paperId, title, authorInformation, timeWithdrawn, timeSubmitted)
            VALUES (42, 'A submitted paper', 'Alice\tExample\talice@x\tExampleU', 0, 100)"""
        .executeUpdate()

      // Withdrawn — must be filtered out.
      SQL"""INSERT INTO Paper(paperId, title, authorInformation, timeWithdrawn, timeSubmitted)
            VALUES (43, 'A withdrawn paper', 'Bob\tExample\tbob@x\tExampleU', 1, 100)"""
        .executeUpdate()

      // Not submitted (draft) — must be filtered out.
      SQL"""INSERT INTO Paper(paperId, title, authorInformation, timeWithdrawn, timeSubmitted)
            VALUES (44, 'A draft paper', 'Carol\tExample\tcarol@x\tExampleU', 0, 0)"""
        .executeUpdate()

      // Submitted paper without any referee PaperOption rows — should appear
      // with an empty referees list.
      SQL"""INSERT INTO Paper(paperId, title, authorInformation, timeWithdrawn, timeSubmitted)
            VALUES (45, 'Paper without referees', 'Eve\tExample\teve@x\tExampleU', 0, 100)"""
        .executeUpdate()

      // PaperOptions: option 1 = supervisor, option 2 = external.
      SQL"""INSERT INTO PaperOption(paperId, optionId, data)
            VALUES (42, 1, 'sup@example.org')""".executeUpdate()
      SQL"""INSERT INTO PaperOption(paperId, optionId, data)
            VALUES (42, 2, 'ext@example.org')""".executeUpdate()
      SQL"""INSERT INTO PaperOption(paperId, optionId, data)
            VALUES (42, 2, 'ext2@example.org')""".executeUpdate()

      // Option with an id not in the mapping — should be ignored.
      SQL"""INSERT INTO PaperOption(paperId, optionId, data)
            VALUES (42, 99, 'unmapped@example.org')""".executeUpdate()

      // Paper-options on filtered-out papers — should not surface (because
      // their parent Paper row is filtered out upstream).
      SQL"""INSERT INTO PaperOption(paperId, optionId, data)
            VALUES (43, 1, 'should-not-appear-1@example.org')""".executeUpdate()
      SQL"""INSERT INTO PaperOption(paperId, optionId, data)
            VALUES (44, 1, 'should-not-appear-2@example.org')""".executeUpdate()
    }
  }

  "HotCRPImporter" should {

    "be enabled in the MySQL test profile" in {
      assume(hotcrpEnabled, "HotCRP importer not enabled (running PG profile)")
      val imp = app.injector.instanceOf[HotCRPImporter]
      imp.isEnabled mustBe true
    }

    "return submitted, non-withdrawn papers only" in {
      assume(hotcrpEnabled, "HotCRP importer not enabled")
      val imp     = app.injector.instanceOf[HotCRPImporter]
      val results = imp.fetch(callFixture())
      val refs    = results.map(_.externalRef)
      refs must contain (Some("42"))
      refs must contain (Some("45"))
      refs must not contain Some("43")  // withdrawn
      refs must not contain Some("44")  // not submitted
    }

    "extract the primary author's name from authorInformation" in {
      assume(hotcrpEnabled, "HotCRP importer not enabled")
      val imp     = app.injector.instanceOf[HotCRPImporter]
      val results = imp.fetch(callFixture())
      val r42     = results.find(_.externalRef.contains("42")).get
      r42.name mustBe "Alice Example"
      r42.notes mustBe Some("A submitted paper")
    }

    "map PaperOption.optionId to the configured role" in {
      assume(hotcrpEnabled, "HotCRP importer not enabled")
      val imp     = app.injector.instanceOf[HotCRPImporter]
      val results = imp.fetch(callFixture())
      val r42     = results.find(_.externalRef.contains("42")).get
      r42.referees.map(_.email).toSet mustBe Set(
        "sup@example.org", "ext@example.org", "ext2@example.org"
      )
      // Order of roles follows the rows; check the multiset.
      r42.referees.map(r => (r.email, r.role)).toSet mustBe Set(
        ("sup@example.org",  Some("supervisor")),
        ("ext@example.org",  Some("external")),
        ("ext2@example.org", Some("external"))
      )
    }

    "ignore PaperOption rows whose optionId is not in the mapping" in {
      assume(hotcrpEnabled, "HotCRP importer not enabled")
      val imp     = app.injector.instanceOf[HotCRPImporter]
      val results = imp.fetch(callFixture())
      val r42     = results.find(_.externalRef.contains("42")).get
      r42.referees.map(_.email) must not contain "unmapped@example.org"
    }

    "yield an empty referees list for papers with no matching PaperOption rows" in {
      assume(hotcrpEnabled, "HotCRP importer not enabled")
      val imp     = app.injector.instanceOf[HotCRPImporter]
      val results = imp.fetch(callFixture())
      val r45     = results.find(_.externalRef.contains("45")).get
      r45.referees mustBe empty
    }
  }
}
