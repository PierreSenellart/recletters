package services.imports

import java.io.ByteArrayInputStream
import org.scalatestplus.play._
import play.api.Configuration

class CsvImporterSpec extends PlaySpec {

  private def parse(csv: String) = {
    val imp = new CsvImporter(Configuration.empty)
    imp.parseStream(new ByteArrayInputStream(csv.getBytes("UTF-8")))
  }

  "CsvImporter" should {
    "group multiple referee rows under the same external_ref" in {
      val csv =
        """external_ref,name,url,notes,referee_email,referee_role
          |42,Alice Example,http://x,Some notes,alice.referee@x,supervisor
          |42,Alice Example,http://x,Some notes,bob.referee@x,external
          |""".stripMargin

      val r = parse(csv)
      r must have size 1
      r.head.externalRef mustBe Some("42")
      r.head.name        mustBe "Alice Example"
      r.head.referees must have size 2
      r.head.referees.map(_.email).toSet mustBe Set("alice.referee@x", "bob.referee@x")
      r.head.referees.map(_.role).toSet  mustBe Set(Some("supervisor"), Some("external"))
    }

    "group by name when external_ref is empty" in {
      val csv =
        """external_ref,name,url,notes,referee_email,referee_role
          |,Carol,,,c1@x,
          |,Carol,,,c2@x,
          |,Dave,,,d@x,
          |""".stripMargin

      val r = parse(csv).sortBy(_.name)
      r.map(_.name) mustBe Seq("Carol", "Dave")
      r.find(_.name == "Carol").get.referees must have size 2
      r.find(_.name == "Dave").get.referees  must have size 1
    }

    "skip rows that have no referee email" in {
      val csv =
        """external_ref,name,url,notes,referee_email,referee_role
          |1,Alice,,,
          |""".stripMargin
      val r = parse(csv)
      r must have size 1
      r.head.referees mustBe empty
    }
  }
}
