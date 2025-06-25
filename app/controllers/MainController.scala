package controllers

import java.util.Base64
import java.util.Calendar
import javax.inject._

import play.api._
import play.api.db._
import play.api.mvc._

import play.api.i18n.I18nSupport
import play.api.i18n.MessagesApi

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

import models.User
import models.UserService

import anorm._

object MainController {
  def current_year = {
    val cal = Calendar.getInstance()
    val year = cal.get(Calendar.YEAR)
    val month = cal.get(Calendar.MONTH) + 1
    if (month <= 4)
      year - 1
    else
      year
  }

  // From https://github.com/scala/scala3/issues/2335#issuecomment-1309916758
  def unapply[P <: Product](p: P)(using
      m: scala.deriving.Mirror.ProductOf[P]
  ): Option[m.MirroredElemTypes] =
    Some(Tuple.fromProductTyped(p))
}

class MainController @Inject() (
)(implicit val db: Database, cc: ControllerComponents, config: Configuration)
    extends AbstractController(cc)
    with I18nSupport {
  import MainController._

  var userService = new UserService(db)

  implicit def years: Seq[Int] =
    db.withConnection { implicit connection =>
      (SQL"""SELECT DISTINCT year FROM dossier"""
        .as(SqlParser.int("year").*)
        .toSet + current_year).toSeq.sorted
    }
  implicit def active_year(implicit request: play.api.mvc.RequestHeader): Int =
    request.session.get("year").map(_.toInt).getOrElse(current_year)
  implicit def user(implicit
      request: play.api.mvc.RequestHeader
  ): Option[User] =
    userId(request).flatMap { userService.find(_) }

  def intro(): Action[AnyContent] = Action { implicit request =>
    Ok(views.html.intro())
  }

  def changeYear(year: Int, redir: String): Action[AnyContent] = Action {
    request =>
      Redirect(redir).withSession(request.session + ("year" -> year.toString()))
  }

  def userId(request: RequestHeader) =
    request.session.get("userid").map(_.toLong)

  def onUnauthorized(request: RequestHeader) =
    Results.Redirect(routes.AuthController.login(Some(request.uri)))

  def withAuth()(f: => Request[AnyContent] => Result) = {
    Security.Authenticated(userId, onUnauthorized) { userId =>
      Action(request => {
        val user = userService.find(userId)
        if (
          !user.isDefined
        )
          Redirect(routes.AuthController.login(Some(request.uri)))
        else
          f(request)
      })
    }
  }
}
