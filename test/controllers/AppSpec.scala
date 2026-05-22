package controllers

import com.typesafe.config.ConfigFactory
import helpers.DBFixtures
import org.scalatestplus.play._
import org.scalatestplus.play.guice._
import play.api.Application
import play.api.Configuration
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test._
import play.api.test.Helpers._

/** End-to-end HTTP-layer tests against a real PostgreSQL or MySQL DB. The
  * engine is picked via -Drecletters.testdb.engine=pg|mysql; see conf/test.conf.
  */
class AppSpec extends PlaySpec with GuiceOneAppPerSuite with DBFixtures {

  override def fakeApplication(): Application = {
    // Pick whichever resource was passed via -Dconfig.resource (defaults to
    // test.conf → PostgreSQL). build.sbt forks the test JVM and forwards the
    // system property so this works under `make test-pg` / `make test-mysql`.
    val resource = sys.props.getOrElse("config.resource", "test.conf")
    GuiceApplicationBuilder()
      .configure(Configuration(ConfigFactory.load(resource)))
      .build()
  }

  // ── Smoke ────────────────────────────────────────────────────────────────

  "GET /" should {
    "return 200" in {
      val r = route(app, FakeRequest(GET, "/")).get
      status(r) mustBe OK
    }
  }

  "GET /login" should {
    "render the form" in {
      val r = route(app, FakeRequest(GET, "/login")).get
      status(r) mustBe OK
      contentAsString(r).toLowerCase must include("password")
    }
  }

  "GET /requests" should {
    "redirect unauthenticated requests to /login" in {
      val r = route(app, FakeRequest(GET, "/requests")).get
      status(r) mustBe SEE_OTHER
      redirectLocation(r).get must include("/login")
    }
  }

  // ── Auth ─────────────────────────────────────────────────────────────────

  "POST /authenticate" should {

    "succeed with valid credentials" in {
      val r = route(app,
        FakeRequest(POST, "/authenticate").withFormUrlEncodedBody(
          "email" -> "admin@test.local", "password" -> "admin", "path" -> ""
        )
      ).get
      status(r) mustBe SEE_OTHER
      session(r).get("userid") must be(defined)
    }

    "reject an unknown email with a 400" in {
      val r = route(app,
        FakeRequest(POST, "/authenticate").withFormUrlEncodedBody(
          "email" -> "nobody@test.local", "password" -> "x", "path" -> ""
        )
      ).get
      status(r) mustBe BAD_REQUEST
    }

    "reject a wrong password with a 400" in {
      val r = route(app,
        FakeRequest(POST, "/authenticate").withFormUrlEncodedBody(
          "email" -> "admin@test.local", "password" -> "wrong", "path" -> ""
        )
      ).get
      status(r) mustBe BAD_REQUEST
    }

    "reject protocol-relative open redirects" in {
      val r = route(app,
        FakeRequest(POST, "/authenticate").withFormUrlEncodedBody(
          "email" -> "admin@test.local", "password" -> "admin", "path" -> "//evil.com"
        )
      ).get
      status(r) mustBe SEE_OTHER
      redirectLocation(r).get mustBe "/"
    }

    "reject absolute-URL redirects" in {
      val r = route(app,
        FakeRequest(POST, "/authenticate").withFormUrlEncodedBody(
          "email" -> "admin@test.local", "password" -> "admin", "path" -> "https://evil.com"
        )
      ).get
      redirectLocation(r).get mustBe "/"
    }
  }

  // ── Password-reset email enumeration ────────────────────────────────────

  "POST /init_password" should {
    "respond identically for unknown and known emails" in {
      val unknown = route(app,
        FakeRequest(POST, "/init_password").withFormUrlEncodedBody("email" -> "nobody@test.local")
      ).get
      val known = route(app,
        FakeRequest(POST, "/init_password").withFormUrlEncodedBody("email" -> "admin@test.local")
      ).get
      status(unknown) mustBe OK
      status(known)   mustBe OK
      // Same view rendered for both; the only content difference is the typed
      // address, which the caller knows already.
      contentAsString(unknown) must include("nobody@test.local")
      contentAsString(known)   must include("admin@test.local")
    }
  }

  // ── Referee token flow ───────────────────────────────────────────────────

  "GET /submit?token=…" should {
    "render the form for a fresh valid token" in {
      val did   = dossierId("Alice Example")
      val token = issueToken(did, "alice.ref1@test.local")
      val r = route(app, FakeRequest(GET, s"/submit?token=$token")).get
      status(r) mustBe OK
      contentAsString(r).toLowerCase must include("alice")
    }

    "reject a bogus token" in {
      val r = route(app, FakeRequest(GET, "/submit?token=does-not-exist")).get
      status(r) mustBe BAD_REQUEST
    }

    "reject an expired token" in {
      val did = dossierId("Alice Example")
      val tok = issueToken(did, "alice.ref1@test.local", ttlDays = -1)
      val r = route(app, FakeRequest(GET, s"/submit?token=$tok")).get
      status(r) mustBe BAD_REQUEST
    }
  }

  "POST /submit (decline)" should {
    "mark the request declined" in {
      val did = dossierId("Bob Example")
      val tok = issueToken(did, "bob.ref@test.local")
      val r = route(app, FakeRequest(POST, "/submit")
        .withMultipartFormDataBody(
          play.api.mvc.MultipartFormData(
            dataParts = Map(
              "token"  -> Seq(tok),
              "status" -> Seq("declined"),
              "name"   -> Seq("Bob Referee")
            ),
            files    = Seq.empty,
            badParts = Seq.empty
          )
        )
      ).get
      status(r) mustBe OK
      refereeStatus(did, "bob.ref@test.local") mustBe "declined"
    }
  }

  "POST /submit (received)" should {
    "store the PDF and mark received" in {
      import java.nio.file.Files
      val did = dossierId("Bob Example")
      val tok = issueToken(did, "bob.ref@test.local")
      val tmp = Files.createTempFile("letter", ".pdf")
      Files.write(tmp, minimalPdf)
      val r = route(app, FakeRequest(POST, "/submit")
        .withMultipartFormDataBody(
          play.api.mvc.MultipartFormData(
            dataParts = Map(
              "token"  -> Seq(tok),
              "status" -> Seq("received"),
              "name"   -> Seq("Bob Referee")
            ),
            files = Seq(
              play.api.mvc.MultipartFormData.FilePart(
                key         = "letter",
                filename    = "letter.pdf",
                contentType = Some("application/pdf"),
                ref         = play.api.libs.Files.SingletonTemporaryFileCreator
                                .create(tmp)
              )
            ),
            badParts = Seq.empty
          )
        )
      ).get
      status(r) mustBe OK
      refereeStatus(did, "bob.ref@test.local") mustBe "received"
    }

    "reject a non-PDF upload" in {
      import java.nio.file.Files
      val did = dossierId("Bob Example")
      val tok = issueToken(did, "bob.ref@test.local")
      val tmp = Files.createTempFile("not-pdf", ".pdf")
      Files.write(tmp, "Not a PDF at all.".getBytes("UTF-8"))
      val r = route(app, FakeRequest(POST, "/submit")
        .withMultipartFormDataBody(
          play.api.mvc.MultipartFormData(
            dataParts = Map(
              "token"  -> Seq(tok),
              "status" -> Seq("received"),
              "name"   -> Seq("Bob Referee")
            ),
            files = Seq(
              play.api.mvc.MultipartFormData.FilePart(
                key         = "letter",
                filename    = "fake.pdf",
                contentType = Some("application/pdf"),
                ref         = play.api.libs.Files.SingletonTemporaryFileCreator
                                .create(tmp)
              )
            ),
            badParts = Seq.empty
          )
        )
      ).get
      status(r) mustBe BAD_REQUEST
      refereeStatus(did, "bob.ref@test.local") must not be "received"
    }
  }

  // ── /api/dossiers/bulk (bearer auth) ────────────────────────────────────

  "POST /api/dossiers/bulk" should {
    val payload =
      """{ "call": "test-call",
        |  "dossiers": [
        |    { "externalRef": "100", "name": "Carol", "url": null, "notes": null,
        |      "referees": [
        |        { "email": "c1@x", "role": "supervisor", "notes": null },
        |        { "email": "c2@x", "role": "external",   "notes": null }
        |      ]
        |    }
        |  ]
        |}""".stripMargin

    "reject without a bearer token" in {
      val r = route(app, FakeRequest(POST, "/api/dossiers/bulk")
        .withHeaders("Content-Type" -> "application/json")
        .withBody(payload)
      ).get
      status(r) mustBe UNAUTHORIZED
    }

    "reject with a wrong bearer token" in {
      val r = route(app, FakeRequest(POST, "/api/dossiers/bulk")
        .withHeaders("Content-Type" -> "application/json",
                     "Authorization" -> "Bearer wrong")
        .withBody(payload)
      ).get
      status(r) mustBe UNAUTHORIZED
    }

    "insert and report counts with the right bearer token" in {
      val r = route(app, FakeRequest(POST, "/api/dossiers/bulk")
        .withHeaders("Content-Type" -> "application/json",
                     "Authorization" -> "Bearer test-token")
        .withBody(payload)
      ).get
      status(r) mustBe OK
      contentAsString(r) must include("\"created\":1")
    }

    "be idempotent: a re-POST updates instead of duplicating" in {
      val first = route(app, FakeRequest(POST, "/api/dossiers/bulk")
        .withHeaders("Content-Type" -> "application/json",
                     "Authorization" -> "Bearer test-token")
        .withBody(payload)
      ).get
      status(first) mustBe OK
      val second = route(app, FakeRequest(POST, "/api/dossiers/bulk")
        .withHeaders("Content-Type" -> "application/json",
                     "Authorization" -> "Bearer test-token")
        .withBody(payload)
      ).get
      status(second) mustBe OK
      contentAsString(second) must include("\"updated\":1")
    }
  }
}
