package controllers

import javax.inject._

import play.api._
import play.api.db._
import play.api.mvc._

import play.api.i18n.I18nSupport

import models.{Call, CallService, User, UserService}

class MainController @Inject() ()(implicit
    val db: Database,
    cc: ControllerComponents,
    config: Configuration
) extends AbstractController(cc)
    with I18nSupport {

  val userService = new UserService(db)
  val callService = new CallService(db)

  /** All calls visible in the selector (everything not archived, plus archived
    * calls still referenced by an active session).
    */
  implicit def calls: Seq[Call] = callService.listVisible()

  /** The session-selected call, falling back to the active one. */
  implicit def active_call(implicit
      request: play.api.mvc.RequestHeader
  ): Option[Call] = {
    val sessionId = request.session.get("call_id").flatMap(_.toIntOption)
    sessionId.flatMap(callService.find).orElse(callService.activeCall())
  }

  implicit def user(implicit
      request: play.api.mvc.RequestHeader
  ): Option[User] =
    userId(request).flatMap(userService.find)

  def intro(): Action[AnyContent] = Action { implicit request =>
    Ok(views.html.intro())
  }

  def changeCall(callId: Int, redir: String): Action[AnyContent] = Action {
    request =>
      // Reject open redirects: only same-origin paths starting with a single '/'.
      val safeRedir =
        if (redir.startsWith("/") && !redir.startsWith("//")) redir
        else routes.MainController.intro().url
      Redirect(safeRedir).withSession(
        request.session + ("call_id" -> callId.toString)
      )
  }

  private def userId(request: RequestHeader): Option[Long] =
    request.session.get("userid").flatMap(_.toLongOption)

  private def onUnauthorized(request: RequestHeader): Result =
    Results.Redirect(routes.AuthController.login(Some(request.uri)))

  def withAuth()(f: => Request[AnyContent] => Result): EssentialAction =
    Security.Authenticated(userId, onUnauthorized) { _ =>
      Action(request => f(request))
    }
}
