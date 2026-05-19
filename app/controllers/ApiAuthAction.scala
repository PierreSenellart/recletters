package controllers

import java.security.MessageDigest
import javax.inject._

import play.api.Configuration
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}

class ApiAuthAction @Inject() (
    parser: BodyParsers.Default
)(implicit ec: ExecutionContext, config: Configuration)
    extends ActionBuilderImpl(parser) {

  private val expected = config.get[String]("api_token").getBytes("UTF-8")

  override def invokeBlock[A](
      request: Request[A],
      block: Request[A] => Future[Result]
  ): Future[Result] = {
    val provided = request.headers
      .get("Authorization")
      .collect { case s if s.startsWith("Bearer ") => s.drop(7).getBytes("UTF-8") }

    val ok = provided.exists(p =>
      p.length == expected.length && MessageDigest.isEqual(p, expected)
    )
    if (ok) block(request) else Future.successful(Results.Unauthorized)
  }
}
