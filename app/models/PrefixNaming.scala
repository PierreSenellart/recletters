package models

import anorm.Macro.ColumnNaming

class PrefixNaming(prefix: String) extends ColumnNaming
{
  def apply(property: String) : String =
    prefix + "." + property
}
