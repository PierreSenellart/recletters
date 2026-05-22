maintainer := "Pierre Senellart <pierre@senellart.com>"
name := """recletters"""
organization := "com.senellart.pierre"

version := "1.0-SNAPSHOT"

lazy val root = (project in file("."))
  .enablePlugins(PlayScala, JavaServerAppPackaging, SystemdPlugin, DebianPlugin)

scalaVersion := "3.3.7"

libraryDependencies ++= Seq(
  jdbc,
  cacheApi,
  ws,
  guice,
  filters,
  evolutions,
  "org.playframework.anorm" %% "anorm" % "2.7.0",
  "org.playframework.anorm" %% "anorm-postgres" % "2.7.0",
  "org.playframework" %% "play-mailer" % "10.1.0",
  "org.playframework" %% "play-mailer-guice" % "10.1.0",
  "org.postgresql" % "postgresql" % "42.7.4",
  "org.mariadb.jdbc" % "mariadb-java-client" % "3.4.1",
  // MariaDB Connector/J needs JNA to talk to a Unix-domain MySQL socket.
  // Pulled in unconditionally so the same package works for socket-auth
  // installations.
  "net.java.dev.jna" % "jna" % "5.14.0",
  "at.favre.lib" % "bcrypt" % "0.10.2",
  "com.github.tototoshi" %% "scala-csv" % "2.0.0",
  "org.scalatestplus.play" %% "scalatestplus-play" % "7.0.1" % Test,
  // Unix-socket factory for PostgreSQL peer-auth in tests.
  "com.kohlschutter.junixsocket" % "junixsocket-core" % "2.10.1" % Test pomOnly()
)

// Tests run serially: they share the test database and TRUNCATE between cases.
Test / parallelExecution := false

// Fork the test JVM so `-Dconfig.resource=…` (and friends) reach the suite,
// instead of staying in the sbt parent JVM only.
Test / fork := true
Test / javaOptions ++= sys.props.collect {
  case (k, v) if k.startsWith("config.") || k.startsWith("recletters.") =>
    s"-D$k=$v"
}.toSeq

scalacOptions += "-feature"

// Tweak default Twirl imports so our `models.Call` shadows `play.api.mvc.Call`
// (the reverse-routing endpoint class) inside templates. Anything that needs
// the mvc value is reached via `routes.*` anyway.
import play.twirl.sbt.Import.TwirlKeys
TwirlKeys.templateImports := Seq(
  "play.twirl.api.TwirlFeatureImports._",
  "play.twirl.api.TwirlHelperImports._",
  "play.twirl.api.Html",
  "play.twirl.api.JavaScript",
  "play.twirl.api.Txt",
  "play.twirl.api.Xml",
  "models._",
  "controllers._",
  "play.api.i18n._",
  "play.api.mvc.{Call => _, _}",
  "play.api.data._"
)

// Debian packaging (sbt-native-packager)
packageSummary := "Recommendation-letter collection web app"
packageDescription := "Play/Scala web application for soliciting and collecting recommendation letters from referees."
debianPackageDependencies := Seq("java21-runtime-headless | java17-runtime-headless")
debianPackageRecommends := Seq("postgresql", "nginx")
