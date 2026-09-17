package controllers

import java.time.ZonedDateTime

import com.typesafe.config.ConfigFactory
import org.scalatestplus.play._
import org.scalatestplus.play.guice._
import play.api.Application
import play.api.Configuration
import play.api.i18n.{Langs, MessagesApi}
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.mailer.{Email, MailerClient}

import models.Call

/** Unit tests for the Bcc archival behaviour of MailerService. A capturing
  * MailerClient records the Email it is handed so we can assert on its bcc
  * field, without touching a real SMTP server.
  */
class MailerServiceSpec extends PlaySpec with GuiceOneAppPerSuite {

  override def fakeApplication(): Application = {
    val resource = sys.props.getOrElse("config.resource", "test.conf")
    GuiceApplicationBuilder()
      .configure(Configuration(ConfigFactory.load(resource)))
      .build()
  }

  /** Records every Email passed to send(); never talks to SMTP. */
  private class CapturingMailer extends MailerClient {
    @volatile var sent: List[Email] = Nil
    def send(data: Email): String = { sent = data :: sent; "captured" }
  }

  /** Build a MailerService whose only wiring difference is the presence or
    * absence of email_bcc. Messages/langs come from the running app so the
    * real message bundles resolve.
    */
  private def service(
      bcc: Option[String],
      signature: Option[String] = None
  ): (MailerService, CapturingMailer) = {
    val mailer = new CapturingMailer
    val base = Map[String, Any](
      "site_name"  -> "Test Site",
      "email_from" -> "from@test.local",
      "site_url"   -> "https://test.local"
    )
    implicit val cfg: Configuration =
      Configuration.from(
        base ++ bcc.map("email_bcc" -> _) ++ signature.map("email_signature" -> _)
      )
    val svc = new MailerService(
      mailer,
      app.injector.instanceOf[MessagesApi],
      app.injector.instanceOf[Langs]
    )(cfg)
    (svc, mailer)
  }

  private def callFixture: Call =
    Call(
      id            = 1,
      slug          = "test",
      label         = "Test Call",
      opens_at      = None,
      deadline      = ZonedDateTime.now().plusDays(30),
      grace_seconds = 0,
      site_name_override  = None,
      email_from_override = None,
      email_signature_override = None,
      is_archived   = false
    )

  /** What follows the "-- " signature delimiter in the last mail sent. */
  private def signatureOf(mailer: CapturingMailer): String =
    mailer.sent.head.bodyText.get.split("\n-- \n", 2)(1).trim

  "MailerService" should {

    "Bcc referee requests when email_bcc is configured" in {
      val (svc, mailer) = service(Some("copy@test.local"))
      svc.sendRefereeRequest(callFixture, "Alice", "ref@test.local", "tok")
      mailer.sent.head.bcc mustBe Seq("copy@test.local")
      mailer.sent.head.to  mustBe Seq("ref@test.local")
    }

    "render the call label into the subject with no leftover placeholder" in {
      val (svc, mailer) = service(None)
      svc.sendRefereeRequest(callFixture, "Alice", "ref@test.local", "tok")
      val subject = mailer.sent.head.subject
      subject must include ("Test Call")
      subject must not include "{"
    }

    "render the call label into the reminder subject too" in {
      val (svc, mailer) = service(None)
      svc.sendRefereeRequestReminder(callFixture, "Alice", "ref@test.local", "tok")
      val subject = mailer.sent.head.subject
      subject must include ("Test Call")
      subject must not include "{"
    }

    "Bcc referee reminders when email_bcc is configured" in {
      val (svc, mailer) = service(Some("copy@test.local"))
      svc.sendRefereeRequestReminder(callFixture, "Alice", "ref@test.local", "tok")
      mailer.sent.head.bcc mustBe Seq("copy@test.local")
    }

    "never Bcc password-init mails, even when email_bcc is set" in {
      val (svc, mailer) = service(Some("copy@test.local"))
      svc.sendPasswordInitEmail("user@test.local", "tok")
      mailer.sent.head.bcc mustBe empty
    }

    "not Bcc anything when email_bcc is unset" in {
      val (svc, mailer) = service(None)
      svc.sendRefereeRequest(callFixture, "Alice", "ref@test.local", "tok")
      mailer.sent.head.bcc mustBe empty
    }

    "treat a blank email_bcc as disabled" in {
      val (svc, mailer) = service(Some("   "))
      svc.sendRefereeRequest(callFixture, "Alice", "ref@test.local", "tok")
      mailer.sent.head.bcc mustBe empty
    }

    "sign as the committee of the site by default, not as the bare site name" in {
      val (svc, mailer) = service(None)
      svc.sendRefereeRequest(callFixture, "Alice", "ref@test.local", "tok")
      val sig = signatureOf(mailer)
      sig must include ("Test Site")
      sig must not be "Test Site"
      sig must not include "{"
    }

    "use the site name override of the call in the default signature" in {
      val (svc, mailer) = service(None)
      val call = callFixture.copy(site_name_override = Some("Other Site"))
      svc.sendRefereeRequestReminder(call, "Alice", "ref@test.local", "tok")
      signatureOf(mailer) must include ("Other Site")
    }

    "sign with email_signature when configured" in {
      val (svc, mailer) = service(None, Some("The Test jury"))
      svc.sendRefereeRequest(callFixture, "Alice", "ref@test.local", "tok")
      signatureOf(mailer) mustBe "The Test jury"
      svc.sendRefereeRequestReminder(callFixture, "Alice", "ref@test.local", "tok")
      signatureOf(mailer) mustBe "The Test jury"
    }

    "prefer the signature override of the call over email_signature" in {
      val (svc, mailer) = service(None, Some("The Test jury"))
      val call = callFixture.copy(email_signature_override = Some("The hiring committee"))
      svc.sendRefereeRequest(call, "Alice", "ref@test.local", "tok")
      signatureOf(mailer) mustBe "The hiring committee"
    }

    "treat a blank email_signature as unset" in {
      val (svc, mailer) = service(None, Some("  "))
      svc.sendRefereeRequest(callFixture, "Alice", "ref@test.local", "tok")
      signatureOf(mailer) must include ("Test Site")
    }
  }
}
