package controllers

import java.security.MessageDigest
import javax.inject._

import play.api.Configuration
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}

/** Bearer-token-authed action builder. The token lives in `api_token` (set in
  * secrets.conf). Compare in constant time to avoid leaking the token via
  * response-time differences.
  */
class ApiAuthAction @Inject() (
    parser: BodyParsers.Default
)(implicit val ec: ExecutionContext, config: Configuration)
    extends ActionBuilderImpl(parser) {

  private val expected = config.get[String]("api_token").getBytes("UTF-8")

  /** Header check, exposed so handlers using a non-default body parser can
    * reuse the same auth logic.
    */
  def authorized(request: RequestHeader): Boolean = {
    val provided = request.headers
      .get("Authorization")
      .collect {
        case s if s.startsWith("Bearer ") => s.drop(7).getBytes("UTF-8")
      }
    provided.exists(p =>
      p.length == expected.length && MessageDigest.isEqual(p, expected)
    )
  }

  override def invokeBlock[A](
      request: Request[A],
      block: Request[A] => Future[Result]
  ): Future[Result] =
    if (authorized(request)) block(request)
    else Future.successful(Results.Unauthorized)
}
