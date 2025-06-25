package controllers

import javax.inject._

import play.api._
import play.api.data._
import play.api.data.Forms._
import play.api.db._
import play.api.i18n.I18nSupport
import play.api.mvc._

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.ExecutionContext

import models.User
import models.UserService

class AuthController @Inject() (
    val passwordRequestFormView: views.html.initPassword,
    mailer: MailerService
)(implicit db: Database, cc: ControllerComponents, config: Configuration)
    extends MainController {
  val loginForm = Form(
    tuple(
      "email" -> nonEmptyText,
      "password" -> nonEmptyText,
      "path" -> text
    ) verifying ("Invalid email or password", result =>
      result match {
        case (email, password, path) => check(email, password)
      })
  )

  val passwordRequestForm = Form(
    single(
      "email" -> nonEmptyText
    ) verifying ("Unknown email", result =>
      result match {
        case email => userService.getId(email).isDefined
      })
  )

  val passwordResetForm = Form(
    tuple(
      "new_password" -> nonEmptyText(minLength = 8),
      "verify_password" -> nonEmptyText(minLength = 8),
      "token" -> nonEmptyText
    ) verifying ("Passwords do not match", result =>
      result match {
        case (n, v, t) => n == v
      })
  )

  def check(username: String, password: String) =
    userService.authenticate(username, password).isDefined

  def login(path: Option[String]): Action[AnyContent] = Action {
    implicit request =>
      Ok(views.html.login(loginForm.fill("", "", path.getOrElse(""))))
  }

  def showInitPassword(): Action[AnyContent] = Action { implicit request =>
    Ok(passwordRequestFormView(passwordRequestForm))
  }

  def initPassword = Action {
    implicit request: Request[AnyContent] =>
      passwordRequestForm.bindFromRequest().fold(
        formWithErrors => {
          BadRequest(passwordRequestFormView(formWithErrors))
        },
        email => {
          mailer.sendPasswordInitEmail(
            email,
            userService.generateToken(userService.getId(email).get)
          )
          Ok(views.html.emailSent(email))
        }
      )
  }

  def showResetPassword(token: String): Action[AnyContent] = Action {
    implicit request =>
      val userId = userService.getUserFromToken(token)

      if (userId.isDefined) {
        val u = userService.find(userId.get).get
        Ok(
          views.html.resetPassword(
            passwordResetForm.fill("", "", token),
            u.first_name.getOrElse("") + " " + u.last_name.getOrElse("")
          )
        )
      } else
        Ok(views.html.passwordReset(false))
  }

  def resetPassword(): Action[AnyContent] = Action { implicit request =>
    passwordResetForm
      .bindFromRequest()
      .fold(
        formWithErrors => BadRequest(views.html.resetPassword(formWithErrors)),
        data =>
          data match {
            case (password, v, token) =>
              val id = userService.getUserFromToken(token)
              if (id.isDefined) {
                userService.removeToken(id.get)
                userService.setPassword(id.get, password)
              }
              Ok(views.html.passwordReset(id.isDefined))
          }
      )
  }

  def authenticate(): Action[AnyContent] = Action { implicit request =>
    loginForm
      .bindFromRequest()
      .fold(
        formWithErrors => BadRequest(views.html.login(formWithErrors)),
        data =>
          Redirect(if (data._3 != "") data._3 else "/").withSession(
            request.session + ("userid" -> userService
              .getId(data._1)
              .get
              .toString)
          )
      )
  }

  def logout(): Action[AnyContent] = Action {
    Redirect(routes.MainController.intro()).withNewSession.flashing(
      "success" -> "You are now logged out."
    )
  }
}
