package models

import scala.reflect.ClassTag
import anorm._

class DBEnum extends Enumeration {
  protected def enumToType[E](convert: String => E)(implicit
      ct: ClassTag[E]
  ): Column[E] = Column { (value, meta) =>
    val MetaDataItem(qualified, nullable, clazz) = meta

    value match {
      case s: String =>
        try {
          Right(convert(s))
        } catch {
          case e: Exception =>
            Left(
              TypeDoesNotMatch(
                s"Cannot convert $value: ${value.getClass} to ${ct.runtimeClass.getSimpleName} for column $qualified"
              )
            )
        }
      case _ =>
        Left(
          TypeDoesNotMatch(
            s"Unexpected value $value: ${value.getClass} for column $qualified"
          )
        )
    }
  }

  protected def createEnumToStatement[E](): ToStatement[E] =
    new ToStatement[E] {
      def set(s: java.sql.PreparedStatement, index: Int, aValue: E): Unit = {
        s.setObject(index, aValue.toString, java.sql.Types.OTHER)
      }
    }
}
