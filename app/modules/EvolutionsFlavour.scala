package modules

import java.io.InputStream
import javax.inject.{Inject, Singleton}

import play.api.{Configuration, Environment}
import play.api.db.evolutions.{
  ApplicationEvolutions,
  ApplicationEvolutionsProvider,
  DefaultEvolutionsApi,
  DefaultEvolutionsConfigParser,
  EvolutionsApi,
  EvolutionsConfig,
  EvolutionsReader,
  ResourceEvolutionsReader
}
import play.api.inject.{Binding, Module}

/** Reads evolution scripts named `<revision>-<flavour>.sql`, picking the
  * flavour from the configured JDBC driver rather than from a hand-maintained
  * `<revision>.sql` symlink. So the same package runs its PostgreSQL or
  * MySQL/MariaDB schema purely on the strength of `db.default.driver`.
  *
  * ResourceEvolutionsReader implements `evolutions(db)` by calling loadResource
  * for revision 1, 2, … until it returns None; we only override the file name.
  */
@Singleton
class FlavoredEvolutionsReader @Inject() (
    environment: Environment,
    config: Configuration
) extends ResourceEvolutionsReader {

  /** PostgreSQL or MySQL/MariaDB, inferred from db.default.driver. Fails fast at
    * startup on an unrecognised driver rather than silently finding no scripts. */
  private val flavour: String = {
    val driver = config.getOptional[String]("db.default.driver").getOrElse("").toLowerCase
    if (driver.contains("postgres")) "postgres"
    else if (driver.contains("mysql") || driver.contains("maria")) "mysql"
    else
      throw new RuntimeException(
        s"Cannot select an evolutions flavour: unrecognised db.default.driver='$driver' " +
          "(expected a PostgreSQL or MySQL/MariaDB driver)."
      )
  }

  def loadResource(db: String, revision: Int): Option[InputStream] =
    environment.resourceAsStream(s"evolutions/$db/$revision-$flavour.sql")
}

/** Drop-in replacement for Play's default `EvolutionsModule`: identical except
  * that `EvolutionsReader` is bound to [[FlavoredEvolutionsReader]]. Enabled
  * (and the default disabled) from `reference.conf`, so it applies uniformly to
  * the app and to every test profile.
  */
class FlavoredEvolutionsModule extends Module {
  def bindings(environment: Environment, configuration: Configuration): Seq[Binding[?]] =
    Seq(
      bind[EvolutionsConfig].toProvider[DefaultEvolutionsConfigParser],
      bind[EvolutionsReader].to[FlavoredEvolutionsReader],
      bind[EvolutionsApi].to[DefaultEvolutionsApi],
      bind[ApplicationEvolutions].toProvider[ApplicationEvolutionsProvider].eagerly()
    )
}
