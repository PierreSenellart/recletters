package controllers

import javax.inject._

import play.api._
import play.api.data._
import play.api.data.Forms._
import play.api.db._
import play.api.mvc._

import models.Call

/** The per-call overrides of the global branding settings. An empty field
  * means "no override": the call uses the global value.
  */
case class CallOverrides(
    site_name: Option[String],
    email_from: Option[String],
    email_signature: Option[String]
)

object CallOverrides {
  def of(call: Call): CallOverrides =
    CallOverrides(
      call.site_name_override,
      call.email_from_override,
      call.email_signature_override
    )

  def unapply(o: CallOverrides): Option[(Option[String], Option[String], Option[String])] =
    Some((o.site_name, o.email_from, o.email_signature))
}

class SettingsController @Inject() (mailer: MailerService)(implicit
    db: Database,
    cc: ControllerComponents,
    config: Configuration
) extends MainController {

  /** One line of at most 255 characters (the column width). These values end
    * up in mail headers and bodies, so control characters are refused.
    */
  private val overrideField: Mapping[Option[String]] =
    optional(
      text(maxLength = 255)
        .verifying("settings.error.controlChars", s => !s.exists(_.isControl))
    ).transform[Option[String]](_.map(_.trim).filter(_.nonEmpty), identity)

  private val form: Form[CallOverrides] = Form(
    mapping(
      "site_name"       -> overrideField,
      "email_from"      -> overrideField,
      "email_signature" -> overrideField
    )(CallOverrides.apply)(CallOverrides.unapply)
  )

  /** The global settings shown on the page, in display order. Only branding
    * and reminder settings: nothing from secrets.conf is ever listed.
    */
  private def globals: Seq[(String, Option[String])] =
    Seq(
      "site_name",
      "site_url",
      "main_site_url",
      "email_from",
      "email_signature",
      "email_bcc",
      "reminder.days",
      "reminder.timezone"
    ).map { key =>
      key -> (
        if (config.has(key)) Some(config.underlying.getAnyRef(key).toString.trim)
        else None
      ).filter(_.nonEmpty)
    }

  private def page(f: Form[CallOverrides])(implicit request: Request[AnyContent]) =
    views.html.settings(f, globals, active_call.map(mailer.signature))

  def index(): EssentialAction = withAuth() { implicit request =>
    Ok(page(active_call.fold(form)(c => form.fill(CallOverrides.of(c)))))
  }

  def update(): EssentialAction = withAuth() { implicit request =>
    active_call match {
      case None => Redirect(routes.SettingsController.index())
      case Some(c) =>
        form.bindFromRequest().fold(
          withErrors => BadRequest(page(withErrors)),
          o => {
            callService.updateOverrides(c.id, o.site_name, o.email_from, o.email_signature)
            Redirect(routes.SettingsController.index())
              .flashing("settings.saved" -> c.label)
          }
        )
    }
  }
}
