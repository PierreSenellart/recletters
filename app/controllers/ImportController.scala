package controllers

import javax.inject._

import play.api._
import play.api.db._
import play.api.libs.json._
import play.api.mvc._

import services.imports._
import services.imports.JsonApiImporter._
import models.Call

class ImportController @Inject() (
    imports: ImportService,
    csvImporter: CsvImporter,
    apiAuth: ApiAuthAction
)(implicit
    db: Database,
    cc: ControllerComponents,
    config: Configuration
) extends MainController {

  def index(): EssentialAction = withAuth() { implicit request =>
    active_call match {
      case None    => Ok(views.html.importIndex(Seq.empty, None, None))
      case Some(c) =>
        val flash = request.flash.get("import.result")
        Ok(views.html.importIndex(imports.listAvailable, Some(c), flash))
    }
  }

  def runImporter(name: String): EssentialAction = withAuth() { implicit request =>
    active_call match {
      case None => Redirect(routes.ImportController.index())
      case Some(c) =>
        imports.run(name, c) match {
          case Right(res) =>
            Redirect(routes.ImportController.index()).flashing(
              "import.result" -> s"$name: ${res.total} (${res.created} new, ${res.updated} updated)"
            )
          case Left(err) =>
            Redirect(routes.ImportController.index()).flashing(
              "import.result" -> s"$name: error: $err"
            )
        }
    }
  }

  def uploadCsv(): EssentialAction = withAuth() { implicit request =>
    active_call match {
      case None => Redirect(routes.ImportController.index())
      case Some(c) =>
        request.body.asMultipartFormData.flatMap(_.file("csv")) match {
          case None =>
            Redirect(routes.ImportController.index()).flashing(
              "import.result" -> "csv: no file uploaded"
            )
          case Some(f) =>
            val items = csvImporter.parseStream(
              new java.io.FileInputStream(f.ref.path.toFile)
            )
            val res = imports.applyAll(c, items)
            Redirect(routes.ImportController.index()).flashing(
              "import.result" ->
                s"csv: ${res.total} (${res.created} new, ${res.updated} updated)"
            )
        }
    }
  }

  /** Bearer-authed JSON bulk endpoint. Body shape:
    * `{ "call": "slug-or-id", "dossiers": [ImportedDossier, ...] }`
    *
    * Reply: `{ "created": n, "updated": n, "total": n }`. Idempotent w.r.t.
    * (call_id, external_ref).
    */
  def apiBulk(): Action[JsValue] = Action(cc.parsers.json) { request =>
    if (!apiAuth.authorized(request)) Results.Unauthorized
    else {
      val payload = request.body
      val callRef = (payload \ "call").asOpt[JsValue].map {
        case JsNumber(n) => Left(n.toInt)
        case JsString(s) => Right(s)
        case other       => Right(other.toString)
      }
      val call: Option[Call] = callRef.flatMap {
        case Left(id)    => callService.find(id)
        case Right(slug) => callService.findBySlug(slug)
      }
      call match {
        case None =>
          Results.NotFound(Json.obj("error" -> "unknown call"))
        case Some(c) =>
          val items =
            (payload \ "dossiers").asOpt[Seq[ImportedDossier]].getOrElse(Seq.empty)
          val res = imports.applyAll(c, items)
          Results.Ok(
            Json.obj(
              "created" -> res.created,
              "updated" -> res.updated,
              "total"   -> res.total
            )
          )
      }
    }
  }
}
