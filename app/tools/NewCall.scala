package tools

import java.sql.Timestamp
import java.time.LocalDate
import java.time.format.DateTimeParseException
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.{Mode, Environment}
import models.CallService

/** CLI to create a new call. Usage:
  *
  *   sbt "runMain tools.NewCall <slug> <label> <deadline-YYYY-MM-DD> [<opens-YYYY-MM-DD>] [<grace-seconds>]"
  */
object NewCall {
  def main(args: Array[String]): Unit = {
    if (args.length < 3) {
      System.err.println(
        "Usage: NewCall <slug> <label> <deadline-YYYY-MM-DD> [<opens-YYYY-MM-DD>] [<grace-seconds>]"
      )
      sys.exit(2)
    }
    val slug     = args(0)
    val label    = args(1)
    val deadline = parseDate(args(2))
    val opensAt  = args.lift(3).filter(_.nonEmpty).map(parseDate)
    val grace    = args.lift(4).map(_.toInt).getOrElse(0)

    val env = Environment.simple(mode = Mode.Prod)
    val app = new GuiceApplicationBuilder().in(env).build()
    try {
      val svc = app.injector.instanceOf[CallService]
      val id  = svc.create(slug, label, deadline, opensAt, grace)
      println(s"Created call '$slug' (id=$id, deadline=${args(2)}).")
    } finally play.api.Play.stop(app)
  }

  private def parseDate(s: String): Timestamp =
    try Timestamp.valueOf(LocalDate.parse(s).atTime(23, 59, 59))
    catch {
      case _: DateTimeParseException =>
        System.err.println(s"Invalid date (expected YYYY-MM-DD): $s")
        sys.exit(2)
    }
}
