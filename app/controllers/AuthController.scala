package controllers

import javax.inject._

import play.api._
import play.api.data._
import play.api.data.Forms._
import play.api.db._
import play.api.mvc._

class AuthController @Inject() (
    val passwordRequestFormView: views.html.initPassword,
    mailer: MailerService
)(implicit db: Database, cc: ControllerComponents, config: Configuration)
    extends MainController {

  // Login form. We validate credentials in a separate step (after the field
  // checks) rather than via a verifying() clause so the form-level error path
  // does not invoke userService.
  val loginForm = Form(
    tuple(
      "email"    -> nonEmptyText,
      "password" -> nonEmptyText,
      "path"     -> text
    )
  )

  // No verifying() here: disclosing whether an address exists in the DB would
  // be an enumeration oracle. We always render the "email sent" confirmation.
  val passwordRequestForm = Form(
    single("email" -> nonEmptyText)
  )

  val passwordResetForm = Form(
    tuple(
      "new_password"    -> nonEmptyText(minLength = 8),
      "verify_password" -> nonEmptyText(minLength = 8),
      "token"           -> nonEmptyText
    ) verifying ("Passwords do not match", {
      case (n, v, _) => n == v
    })
  )

  /** Same-origin paths only; rejects "//evil.com", "http://...", etc. */
  private def safePath(p: String): Option[String] =
    Option(p).filter(s => s.startsWith("/") && !s.startsWith("//"))

  def login(path: Option[String]): Action[AnyContent] = Action {
    implicit request =>
      Ok(views.html.login(loginForm.fill("", "", path.getOrElse(""))))
  }

  def showInitPassword(): Action[AnyContent] = Action { implicit request =>
    Ok(passwordRequestFormView(passwordRequestForm))
  }

  def initPassword: Action[AnyContent] = Action { implicit request =>
    passwordRequestForm
      .bindFromRequest()
      .fold(
        formWithErrors => BadRequest(passwordRequestFormView(formWithErrors)),
        email => {
          // Always render the same response, regardless of whether the address
          // exists, otherwise the response time and content would let an
          // attacker enumerate registered committee members.
          userService.getId(email).foreach { uid =>
            mailer.sendPasswordInitEmail(email, userService.generateToken(uid))
          }
          Ok(views.html.emailSent(email))
        }
      )
  }

  def showResetPassword(token: String): Action[AnyContent] = Action {
    implicit request =>
      userService.getUserFromToken(token) match {
        case Some(uid) =>
          userService.find(uid) match {
            case Some(u) =>
              Ok(
                views.html.resetPassword(
                  passwordResetForm.fill("", "", token),
                  u.first_name.getOrElse("") + " " + u.last_name.getOrElse("")
                )
              )
            case None => Ok(views.html.passwordReset(false))
          }
        case None => Ok(views.html.passwordReset(false))
      }
  }

  def resetPassword(): Action[AnyContent] = Action { implicit request =>
    passwordResetForm
      .bindFromRequest()
      .fold(
        formWithErrors => BadRequest(views.html.resetPassword(formWithErrors)),
        { case (password, _, token) =>
          userService.getUserFromToken(token) match {
            case Some(id) =>
              userService.removeToken(id)
              userService.setPassword(id, password)
              Ok(views.html.passwordReset(true))
            case None =>
              Ok(views.html.passwordReset(false))
          }
        }
      )
  }

  def authenticate(): Action[AnyContent] = Action { implicit request =>
    loginForm
      .bindFromRequest()
      .fold(
        formWithErrors => BadRequest(views.html.login(formWithErrors)),
        { case (email, password, redirectPath) =>
          userService.authenticate(email, password) match {
            case Some(u) =>
              val target = safePath(redirectPath)
                .getOrElse(routes.MainController.intro().url)
              Redirect(target).withSession(
                request.session + ("userid" -> u.id.toString)
              )
            case None =>
              val formWithError = loginForm
                .fill(email, "", redirectPath)
                .withGlobalError("Invalid email or password")
              BadRequest(views.html.login(formWithError))
          }
        }
      )
  }

  def logout(): Action[AnyContent] = Action {
    Redirect(routes.MainController.intro()).withNewSession.flashing(
      "success" -> "You are now logged out."
    )
  }
}
