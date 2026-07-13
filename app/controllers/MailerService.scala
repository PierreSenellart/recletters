package controllers

import java.time.format.{DateTimeFormatter, FormatStyle}
import java.util.Locale
import javax.inject.Inject

import play.api._
import play.api.i18n.{Lang, Langs, MessagesApi}
import play.api.libs.mailer._

import models.Call

class MailerService @Inject() (
    mailerClient: MailerClient,
    messagesApi: MessagesApi,
    langs: Langs
)(implicit config: Configuration) {

  private val globalSite: String = config.get[String]("site_name")
  private val globalFrom: String = config.get[String]("email_from")

  /** Optional blind-copy address for referee-facing mail, so the committee
    * keeps an archive of what went out (the SMTP log is metadata-only and
    * short-lived). Empty/unset disables it. Password mails are never Bcc'd —
    * they carry reset links.
    */
  private val bcc: Seq[String] =
    config.getOptional[String]("email_bcc").map(_.trim).filter(_.nonEmpty).toSeq

  private def lang: Lang = langs.preferred(langs.availables)

  private def msgs                       = messagesApi.preferred(Seq(lang))
  private def site(call: Option[Call])   =
    call.flatMap(_.site_name_override).getOrElse(globalSite)
  private def from(call: Option[Call])   =
    call.flatMap(_.email_from_override).getOrElse(globalFrom)

  private def formatDeadline(call: Call): String = {
    val locale = Locale.forLanguageTag(lang.code)
    val fmt    = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale)
    call.deadline.format(fmt)
  }

  /** Always-translated body. The deadline for password emails is implicit
    * (48h); the call is not relevant here.
    */
  def sendPasswordInitEmail(to: String, token: String): Unit = {
    val url     = config.get[String]("site_url") + "/reset_password?token=" + token
    val subject = msgs("email.passwordReset.subject", globalSite)
    val body    = msgs("email.passwordReset.body", globalSite, url, to)
    mailerClient.send(
      Email(subject = subject, from = globalFrom, to = Seq(to), bodyText = Some(body))
    )
  }

  def sendRefereeRequest(call: Call, applicant: String, to: String, token: String): Unit = {
    val url      = config.get[String]("site_url") + "/submit?token=" + token
    val deadline = formatDeadline(call)
    val s        = site(Some(call))
    // Args (in this order): applicant, call_label, site_name, url, deadline.
    val subject  = msgs("email.refereeRequest.subject", call.label)
    val body     = msgs("email.refereeRequest.body", applicant, call.label, s, url, deadline)
    mailerClient.send(
      Email(subject = subject, from = from(Some(call)), to = Seq(to), bcc = bcc, bodyText = Some(body))
    )
  }

  def sendRefereeRequestReminder(
      call: Call,
      applicant: String,
      to: String,
      token: String
  ): Unit = {
    val url      = config.get[String]("site_url") + "/submit?token=" + token
    val deadline = formatDeadline(call)
    val s        = site(Some(call))
    val subject  = msgs("email.refereeRequest.reminder.subject", call.label)
    val body = msgs(
      "email.refereeRequest.reminder.body",
      applicant,
      call.label,
      s,
      url,
      deadline
    )
    mailerClient.send(
      Email(subject = subject, from = from(Some(call)), to = Seq(to), bcc = bcc, bodyText = Some(body))
    )
  }
}
