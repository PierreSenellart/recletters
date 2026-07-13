package i18n

import scala.io.Source

import org.scalatestplus.play._

/** Structural lint over the message bundles. java.text.MessageFormat leaves an
  * index with no corresponding argument in the output verbatim (e.g. a subject
  * of "{1}: referee request" when only one argument is passed), so a wrong
  * index fails silently at send time rather than raising. We can't make Play
  * throw, but we can reject the most common shape of that mistake here: a
  * message that references {n} without also referencing every lower index. A
  * placeholder set must be dense and start at {0}.
  */
class MessagesFormatSpec extends PlaySpec {

  private val files       = Seq("conf/messages", "conf/messages.fr")
  private val placeholder = """\{(\d+)""".r

  private def entries(path: String): Seq[(String, String)] = {
    val src = Source.fromFile(path, "UTF-8")
    try
      src.getLines()
        .map(_.trim)
        .filter(l => l.nonEmpty && !l.startsWith("#") && l.contains("="))
        .map { l =>
          val i = l.indexOf('=')
          (l.take(i).trim, l.drop(i + 1).trim)
        }
        .toList
    finally src.close()
  }

  "Every message" should {
    "use dense MessageFormat indices starting at {0} (no gaps)" in {
      val offenders =
        for {
          f            <- files
          (key, value) <- entries(f)
          idx = placeholder.findAllMatchIn(value).map(_.group(1).toInt).toSet
          if idx.nonEmpty && idx != (0 to idx.max).toSet
        } yield s"$f: $key uses ${idx.toList.sorted.mkString("{", ",", "}")}"

      withClue("Messages with non-dense placeholder indices:\n" + offenders.mkString("\n") + "\n") {
        offenders mustBe empty
      }
    }
  }
}
