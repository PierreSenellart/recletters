package tools

import play.api.inject.guice.GuiceApplicationBuilder
import play.api.{Mode, Environment}
import models.UserService

/** CLI to add a committee user. Usage:
  *
  *   sbt "runMain tools.AddUser <email> <first_name|-> <last_name|-> [<password>]"
  *
  * In the .deb / sbt-stage outputs this is exposed as `recletters-adduser`.
  */
object AddUser {
  def main(args: Array[String]): Unit = {
    if (args.length < 3) {
      System.err.println(
        "Usage: AddUser <email> <first_name|-> <last_name|-> [<password>]"
      )
      sys.exit(2)
    }
    val email     = args(0)
    val firstName = Option(args(1)).filter(_ != "-")
    val lastName  = Option(args(2)).filter(_ != "-")
    val password  = args.lift(3).getOrElse {
      val cons = System.console()
      if (cons == null) {
        System.err.println("Password (set as 4th argument) required when stdin is not a TTY.")
        sys.exit(2)
      }
      new String(cons.readPassword("Password: "))
    }

    val env = Environment.simple(mode = Mode.Prod)
    val app = new GuiceApplicationBuilder()
      .in(env)
      .build()
    try {
      val svc = app.injector.instanceOf[UserService]
      val id  = svc.create(email, firstName, lastName, password)
      println(s"Created user $email with id=$id.")
    } finally play.api.Play.stop(app)
  }
}
