package controllers

import javax.inject.Inject

import play.api._
import play.api.mvc._
import play.api.libs.mailer._

class MailerService @Inject() (mailerClient: MailerClient)(implicit config: Configuration)
{
  val site = config.get[String]("site_name")
  val from = config.get[String]("email_from")

  def sendPasswordInitEmail(to: String, token: String) = {
    val url = config.get[String]("site_url") + "/reset_password?token=" + token

    val text = ("""You have requested to reset the password needed to access the
                  |Web site of """ + site + """ Letters.
                  |
                  |To do so, please access the following URL:"""+
                  "\n"+url+"""
                  |
                  |This link is only valid for 48 hours. Past this time, you will
                  |need to apply for a new password reset link.
                  |
                  |You will then be able to log in using your email ("""+to+").").stripMargin

    val email = Email(
      site +" Letters Password Reinitialization",
      from,
      Seq(to),
      bodyText = Some(text),
    )
    mailerClient.send(email)
  }

  def sendRefereeRequest(name: String, to: String, token: String) = {
    val url = config.get[String]("site_url") + "/submit?token=" + token;
    val deadline = config.get[String]("deadline")

    val text = ("""Hello,
                  |
                  |""" + name + """ indicated you as a referee for their application
                  |to the """+ site +""".
                  |
                  |Please access the following URL to provide your reference (or to decline
                  |to do so).
                  |
                  |  """ + url + """
                  |
                  |We need to receive your letter by """ + deadline +++ """.
                  |
                  |Thank you in advance,
                  |
                  |-- """+"""
                  |""" + site + """ committee""").stripMargin

    val email = Email(
      site+" Referee Request",
      from,
      Seq(to),
      bodyText = Some(text),
    )
    mailerClient.send(email)
  }

}
