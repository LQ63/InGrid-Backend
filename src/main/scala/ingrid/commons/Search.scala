package ingrid.commons

/** Freitextsuche für den `q`-Parameter: ohne Beachtung von Gross-/Kleinschreibung,
  * Treffer sobald ein Feld den Suchbegriff enthält.
  */
object Search:
  def matches(q: Option[String], fields: String*): Boolean =
    q.map(_.trim).filter(_.nonEmpty) match
      case None => true
      case Some(needle) =>
        val lower = needle.toLowerCase
        fields.exists(_.toLowerCase.contains(lower))
