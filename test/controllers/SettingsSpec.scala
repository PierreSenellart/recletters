package controllers

import anorm._
import anorm.SqlParser._
import com.typesafe.config.ConfigFactory
import helpers.DBFixtures
import org.scalatestplus.play._
import org.scalatestplus.play.guice._
import play.api.Application
import play.api.Configuration
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test._
import play.api.test.CSRFTokenHelper._
import play.api.test.Helpers._

/** The settings page: read-only global settings, editable per-call overrides. */
class SettingsSpec extends PlaySpec with GuiceOneAppPerSuite with DBFixtures {

  override def fakeApplication(): Application = {
    val resource = sys.props.getOrElse("config.resource", "test.conf")
    GuiceApplicationBuilder()
      .configure(Configuration(ConfigFactory.load(resource)))
      .build()
  }

  /** The fixtures insert a single user, so its id is 1 on both engines. */
  private def asAdmin[A](r: FakeRequest[A]) = r.withSession("userid" -> "1")

  private def overrides(): (Option[String], Option[String], Option[String]) =
    db.withConnection { implicit c =>
      SQL"""SELECT site_name_override, email_from_override,
                   email_signature_override
            FROM call_ WHERE slug='test-call'"""
        .as(
          (get[Option[String]](1) ~ get[Option[String]](2) ~ get[Option[String]](3))
            .map { case a ~ b ~ c => (a, b, c) }
            .single
        )
    }

  private def post(fields: (String, String)*) =
    route(app,
      asAdmin(FakeRequest(POST, "/settings"))
        .withFormUrlEncodedBody(fields: _*)
        .withCSRFToken
    ).get

  "GET /settings" should {

    "redirect unauthenticated requests to /login" in {
      val r = route(app, FakeRequest(GET, "/settings")).get
      status(r) mustBe SEE_OTHER
      redirectLocation(r).get must include("/login")
    }

    "show the global settings and the signature currently in use" in {
      val r = route(app, asAdmin(FakeRequest(GET, "/settings")).withCSRFToken).get
      status(r) mustBe OK
      val body = contentAsString(r)
      body must include("site_name")
      body must include("email_from")
      body must include("Test Call")
      // No override and no email_signature in test.conf: the default applies.
      body must include(app.configuration.get[String]("site_name"))
    }

    "never show secrets" in {
      val r = route(app, asAdmin(FakeRequest(GET, "/settings")).withCSRFToken).get
      val body = contentAsString(r)
      body must not include "api_token"
      body must not include "password"
    }
  }

  "POST /settings" should {

    "redirect unauthenticated requests to /login and change nothing" in {
      val r = route(app,
        FakeRequest(POST, "/settings")
          .withFormUrlEncodedBody("email_signature" -> "Intruder")
          .withCSRFToken
      ).get
      status(r) mustBe SEE_OTHER
      redirectLocation(r).get must include("/login")
      overrides() mustBe ((None, None, None))
    }

    "store the overrides, trimmed, and show the new signature" in {
      val r = post(
        "site_name"       -> "Other Site",
        "email_from"      -> "Jury <jury@test.local>",
        "email_signature" -> "  The jury  "
      )
      status(r) mustBe SEE_OTHER
      overrides() mustBe
        ((Some("Other Site"), Some("Jury <jury@test.local>"), Some("The jury")))

      val page = route(app, asAdmin(FakeRequest(GET, "/settings")).withCSRFToken).get
      contentAsString(page) must include("The jury")
    }

    "clear an override when its field is left empty" in {
      post("site_name" -> "Other Site", "email_from" -> "", "email_signature" -> "The jury")
      post("site_name" -> "", "email_from" -> "", "email_signature" -> "  ")
      overrides() mustBe ((None, None, None))
    }

    "refuse line breaks, which would otherwise reach mail headers" in {
      val r = post(
        "site_name"       -> "",
        "email_from"      -> "a@test.local\r\nBcc: evil@test.local",
        "email_signature" -> ""
      )
      status(r) mustBe BAD_REQUEST
      overrides() mustBe ((None, None, None))
    }

    "refuse values longer than the column" in {
      val r = post("site_name" -> ("x" * 256), "email_from" -> "", "email_signature" -> "")
      status(r) mustBe BAD_REQUEST
      overrides() mustBe ((None, None, None))
    }
  }
}
