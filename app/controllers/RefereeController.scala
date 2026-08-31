package controllers

import java.nio.file.Files
import java.time.{LocalDate, ZoneId, ZonedDateTime}
import java.time.temporal.ChronoUnit
import javax.inject._

import play.api._
import play.api.data._
import play.api.data.Forms._
import play.api.db._
import play.api.mvc._

import models.{
  Call,
  Dossier,
  RefereeRequest,
  RefereeRequestService,
  RequestStatus
}
import services.imports.{ImportResult, ImportService}

class RefereeController @Inject() (
    mailer: MailerService,
    apiAuth: ApiAuthAction,
    imports: ImportService
)(implicit
    db: Database,
    cc: ControllerComponents,
    config: Configuration
) extends MainController {

  val model = new RefereeRequestService(db)

  private val reminderZone: ZoneId =
    ZoneId.of(config.getOptional[String]("reminder.timezone").getOrElse("UTC"))
  private val reminderDays: Long =
    config.getOptional[Long]("reminder.days").getOrElse(7L)
  // 10 MiB upload cap; matches play.http.parser.maxDiskBuffer in conf.
  private val MaxLetterBytes: Long =
    config.getOptional[Long]("letter.maxBytes").getOrElse(10L * 1024 * 1024)

  private def doSendRequestEmails(call: Call): Int = {
    val rs = model.findAll(call.id, Some(RequestStatus.news))
    for (r <- rs) {
      val token = model.generateToken(r)
      mailer.sendRefereeRequest(call, r.dossier.name, r.email, token)
    }
    rs.size
  }

  private def doSendRequestReminderEmails(call: Call): Int = {
    var n = 0
    for (r <- model.findAll(call.id, Some(RequestStatus.requested))) {
      val daysSince = ChronoUnit.DAYS.between(
        r.status_update.toLocalDate,
        LocalDate.now(reminderZone)
      )
      if (daysSince >= reminderDays) {
        // The original token plaintext is unknown to us (we only stored the
        // SHA-256); re-issue a fresh one so the reminder link is usable.
        val token = model.generateToken(r)
        mailer.sendRefereeRequestReminder(call, r.dossier.name, r.email, token)
        model.updateStatusTime(r)
        n += 1
      }
    }
    n
  }

  private def withActiveCall(
      f: Call => Request[AnyContent] => Result
  ): EssentialAction = withAuth() { implicit request =>
    active_call match {
      case Some(c) => f(c)(request)
      case None    => NotFound("No active call configured.")
    }
  }

  def list(): EssentialAction = withActiveCall { call => implicit request =>
    Ok(
      views.html.refereeRequests(
        model.findAll(call.id),
        imports.listPullable.nonEmpty,
        refreshCounts(request.flash.get("refresh"))
      )
    )
  }

  /** Re-read the metadata of every upstream that can pull (HotCRP and friends)
    * for the current call, so a title or referee fixed upstream after the first
    * import shows up here without waiting for the next cron run. Idempotent:
    * dossiers matched by external_ref are updated in place, unchanged ones are
    * not written at all, and no mail is sent.
    *
    * Redirect-after-POST, so reloading the resulting page does not re-import;
    * the counts travel in the flash as "created,updated,unchanged".
    */
  def refresh(): EssentialAction = withActiveCall { call => implicit request =>
    val res = imports.refreshAll(call)
    Redirect(routes.RefereeController.list())
      .flashing("refresh" -> s"${res.created},${res.updated},${res.unchanged}")
  }

  private def refreshCounts(flash: Option[String]): Option[ImportResult] =
    flash.map(_.split(',').toSeq).collect {
      case Seq(c, u, n) if Seq(c, u, n).forall(v => v.nonEmpty && v.forall(_.isDigit)) =>
        ImportResult(c.toInt, u.toInt, n.toInt)
    }

  def form(call: Call): Form[RefereeRequest] = Form(
    mapping(
      "dossier_name"    -> text,
      "dossier_details" -> optional(text),
      "dossier_url"     -> optional(text),
      "email"           -> text,
      "details"         -> optional(text),
      "role"            -> optional(text)
    )((dn, dd, du, e, d, role) =>
      RefereeRequest(
        Dossier(-1, call.id, dn, None, dd, du),
        e,
        d,
        role,
        None,
        RequestStatus.news,
        ZonedDateTime.now()
      )
    )(r =>
      Some((r.dossier.name, r.dossier.details, r.dossier.url, r.email, r.details, r.role))
    )
  )

  def showAdd(): EssentialAction = withActiveCall { call => implicit request =>
    Ok(views.html.form_add(form(call)))
  }

  def add(): EssentialAction = withActiveCall { call => implicit request =>
    form(call).bindFromRequest().fold(
      formWithErrors => BadRequest(views.html.form_add(formWithErrors)),
      r => {
        model.add(r)
        Redirect(routes.RefereeController.list())
      }
    )
  }

  def sendRequestEmails(): EssentialAction = withActiveCall {
    call => implicit request =>
      doSendRequestEmails(call)
      Redirect(routes.RefereeController.list())
  }

  def sendRequestReminderEmails(): EssentialAction = withActiveCall {
    call => implicit request =>
      doSendRequestReminderEmails(call)
      Redirect(routes.RefereeController.list())
  }

  def apiSendRequestEmails(callSlug: String): Action[AnyContent] = apiAuth {
    _ =>
      callService.findBySlug(callSlug) match {
        case Some(c) => Ok(s"sent ${doSendRequestEmails(c)}\n")
        case None    => NotFound(s"Unknown call: $callSlug\n")
      }
  }

  def apiSendRequestReminderEmails(callSlug: String): Action[AnyContent] = apiAuth {
    _ =>
      callService.findBySlug(callSlug) match {
        case Some(c) => Ok(s"sent ${doSendRequestReminderEmails(c)}\n")
        case None    => NotFound(s"Unknown call: $callSlug\n")
      }
  }

  def showSubmit(token: String): Action[AnyContent] = Action { implicit request =>
    model.findByRefereeToken(token) match {
      case Some(r) => Ok(views.html.form_submit(Some(r), token, None))
      case None    => BadRequest(views.html.form_submit(None, token, None))
    }
  }

  /** Magic-byte PDF check: accepts %PDF- as the first five bytes. */
  private def isPdf(bytes: Array[Byte]): Boolean =
    bytes.length >= 5 &&
      bytes(0) == 0x25 && bytes(1) == 0x50 && bytes(2) == 0x44 &&
      bytes(3) == 0x46 && bytes(4) == 0x2d

  def submit(): Action[AnyContent] = Action { implicit request =>
    val resultOpt = for {
      data    <- request.body.asMultipartFormData
      params  = data.asFormUrlEncoded
      tokenS  <- params.get("token").flatMap(_.headOption)
      statusS <- params.get("status").flatMap(_.headOption)
      nameS   <- params.get("name").flatMap(_.headOption)
      r       <- model.findByRefereeToken(tokenS)
    } yield {
      statusS match {
        case "received" =>
          data.file("letter") match {
            case Some(f) =>
              val path = f.ref.path
              if (Files.size(path) > MaxLetterBytes) {
                EntityTooLarge(
                  views.html.form_submit(Some(r), tokenS, Some(false))
                )
              } else {
                val bytes = Files.readAllBytes(path)
                if (!isPdf(bytes)) {
                  BadRequest(
                    views.html.form_submit(Some(r), tokenS, Some(false))
                  )
                } else {
                  model.receiveLetter(r, nameS, bytes)
                  Ok(
                    views.html.form_submit(
                      model.findByRefereeToken(tokenS),
                      tokenS,
                      Some(true)
                    )
                  )
                }
              }
            case None =>
              BadRequest(views.html.form_submit(Some(r), tokenS, Some(false)))
          }
        case _ =>
          model.setDeclined(r)
          Ok(
            views.html.form_submit(
              model.findByRefereeToken(tokenS),
              tokenS,
              Some(true)
            )
          )
      }
    }
    resultOpt.getOrElse(BadRequest("Invalid submission."))
  }

  def getLetterByToken(token: String): Action[AnyContent] = Action {
    model.findByRefereeToken(token) match {
      case None => NotFound("Invalid or expired token.")
      case Some(r) =>
        model.getLetter(r.dossier.id, r.email) match {
          case None        => NotFound("Letter not found.")
          case Some(bytes) => letterResponse(bytes, r.email)
        }
    }
  }

  def getLetter(id: Long, email: String): EssentialAction = withAuth() { _ =>
    model.getLetter(id, email) match {
      case None        => NotFound("Letter not found.")
      case Some(bytes) => letterResponse(bytes, email)
    }
  }

  private def letterResponse(bytes: Array[Byte], email: String): Result = {
    val safeName =
      email.replaceAll("[^A-Za-z0-9._-]", "_") + ".pdf"
    Ok(bytes)
      .as("application/pdf")
      .withHeaders(
        "Content-Disposition"     -> s"""attachment; filename="$safeName"""",
        "X-Content-Type-Options"  -> "nosniff"
      )
  }
}
