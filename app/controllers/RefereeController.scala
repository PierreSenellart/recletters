package controllers

import javax.inject._

import play.api._
import play.api.db._
import play.api.mvc._

import models.Dossier
import models.RefereeRequest
import models.RequestStatus
import models.RefereeRequestService

import play.api.data._
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import java.time.ZonedDateTime

import play.api.data.FormBinding.Implicits.formBinding
import java.nio.file.Files

class RefereeController @Inject() (
    mailer: MailerService
)(implicit db: Database, cc: ControllerComponents, config: Configuration)
    extends MainController {
  val model = new RefereeRequestService(db)

  def list() = withAuth() {
    implicit request =>
      val requests = model.findAll(active_year)

      Ok(views.html.refereeRequests(requests))
  }

  def form(year: Int): Form[RefereeRequest] = Form(
    mapping(
      "dossier_name" -> text,
      "dossier_details" -> optional(text),
      "dossier_url" -> optional(text),
      "email" -> text,
      "details" -> optional(text)
    )((dn, dd, du, e, d) =>
      RefereeRequest(
        Dossier(
          -1,
          year,
          dn,
          dd,
          du
        ),
        e,
        d,
        None,
        RequestStatus.news,
        ZonedDateTime.now()
      )
    )(r =>
      Some(
        r.dossier.name,
        r.dossier.details,
        r.dossier.url,
        r.email,
        r.details
      )
    )
  )

  def showAdd() = withAuth() {
    implicit request =>
      val f = form(active_year)
      Ok(views.html.form_add(f))
  }

  def add() = withAuth() {
    implicit request =>
      val f = form(active_year).bindFromRequest()
      f.fold(
        formWithErrors => {
          BadRequest(
            views.html.form_add(formWithErrors)
          )
        },
        r =>
          val id = model.add(r)
          Redirect(routes.RefereeController.list())
      )
  }

  def sendRequestEmails() : Action[AnyContent] = Action {
    implicit request =>
      for (r <- model.findAll(active_year, Some(RequestStatus.news))) {
        mailer.sendRefereeRequest(r.dossier.name, r.email, model.generateToken(r))
      }
      Redirect(routes.RefereeController.list())
  }

  def showSubmit(token: String) : Action[AnyContent] = Action {
    implicit request =>
      val r = model.findByRefereeToken(token)
      if(r.isDefined)
        Ok(views.html.form_submit(r, token, None))
      else
        BadRequest(views.html.form_submit(None, token, None))
  }

  def submit() : Action[AnyContent] = Action {
    implicit request =>
      val data = request.body.asMultipartFormData.get
      val params = data.asFormUrlEncoded
      val file = data.file("letter")

      val token = params.get("token").get(0)
      val r = model.findByRefereeToken(token)
      val status = params.get("status").get(0)
      val name = params.get("name").get(0)

      if(status == "received") {
        val x = r.get
        val y = file.get
        model.receiveLetter(r.get, name, Files.readAllBytes(file.get.ref.path))
      } else {
        model.setDeclined(r.get)
      }

      Ok(views.html.form_submit(model.findByRefereeToken(token), token, Some(true)))
  }

  def getLetterByToken(token: String) : Action[AnyContent] = Action {
    val r = model.findByRefereeToken(token)
    if(!r.isDefined)
      NotFound("Invalid token "+token+".")
    else {
      val bytes = model.getLetter(r.get.dossier.id, r.get.email)

      if (!bytes.isDefined)
        NotFound("Letter not found.")
      else
        Ok(bytes.get).as("application/pdf")
    }
  }

  def getLetter(id: Long, email: String) = withAuth() {
    implicit request =>
      val bytes = model.getLetter(id, email)

      if (!bytes.isDefined)
        NotFound("Letter not found.")
      else
        Ok(bytes.get).as("application/pdf")
  }
}
