maintainer := "Pierre Senellart <pierre@senellart.com>"
name := """recletters"""
organization := "com.senellart.pierre"

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "3.5.2"

libraryDependencies += jdbc
libraryDependencies += cacheApi
libraryDependencies += ws
libraryDependencies += guice
libraryDependencies += filters
libraryDependencies += "org.playframework.anorm" %% "anorm" % "2.7.0"
libraryDependencies += "org.playframework.anorm" %% "anorm-postgres" % "2.7.0"
libraryDependencies += "org.playframework" %% "play-mailer" % "10.1.0"
libraryDependencies += "org.playframework" %% "play-mailer-guice" % "10.1.0"
libraryDependencies += "org.postgresql" % "postgresql" % "42.7.4"

scalacOptions += "-feature"
