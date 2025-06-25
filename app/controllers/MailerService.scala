package controllers

import javax.inject.Inject

import play.api._
import play.api.mvc._
import play.api.libs.mailer._

class MailerService @Inject() (mailerClient: MailerClient)(implicit config: Configuration)
{
  def sendPasswordInitEmail(to: String, token: String) = {
    val url = config.get[String]("site_url") + "/reset_password?token=" + token;

    val text = ("""You have requested to reset the password needed to access the
                  |Web site of """ + config.get[String]("site_name") + """ Letters.
                  |
                  |To do so, please access the following URL:"""+
                  "\n"+url+"""
                  |
                  |This link is only valid for 48 hours. Past this time, you will
                  |need to apply for a new password reset link.
                  |
                  |You will then be able to log in using your email ("""+to+").").stripMargin

    val email = Email(
      config.get[String]("site_name")+" Letters Password Reinitialization",
      "Pierre Senellart <pierre@senellart.com>",
      Seq(to),
      bodyText = Some(text),
    )
    mailerClient.send(email)
  }

}
