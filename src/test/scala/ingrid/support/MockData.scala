package ingrid.support

import io.circe.Json
import io.circe.parser.parse
import scala.io.{Codec, Source}

/** Zugriff auf `raumplanung-mockdata.json` als Referenz für die Vertragstests.
  *
  * Die Datei enthält neben dem Datensatz fertige Beispielantworten unter `responses`; sie ist
  * damit die Soll-Vorgabe für die berechneten Felder.
  */
object MockData:

  lazy val root: Json =
    val stream = getClass.getResourceAsStream("/seed/raumplanung-mockdata.json")
    val raw =
      try Source.fromInputStream(stream)(using Codec.UTF8).mkString
      finally stream.close()
    parse(raw).fold(e => throw new IllegalStateException(s"Demodaten unlesbar: $e"), identity)

  def section(name: String): Json =
    root.hcursor.downField(name).focus.getOrElse(sys.error(s"Abschnitt $name fehlt"))

  /** Eine der vorgefertigten Beispielantworten, z. B.
    * `GET /rooms/a-1.02/schedule?termId=ws2026`.
    */
  def response(key: String): Json =
    root.hcursor
      .downField("responses")
      .downField(key)
      .focus
      .getOrElse(sys.error(s"Beispielantwort '$key' fehlt"))

  def events: Vector[Json] = section("events").asArray.getOrElse(Vector.empty)

  /** Tiefe Normalform: Objektschlüssel und Array-Elemente sortiert.
    *
    * Damit sind Vergleiche unabhängig von der Reihenfolge. Wo die Reihenfolge fachlich zählt
    * (Konfliktreihenfolge, 30 Zellen der Vorschau), wird sie separat geprüft.
    */
  def canonical(j: Json): Json =
    j.arrayOrObject(
      j,
      arr => Json.fromValues(arr.map(canonical).sortBy(_.noSpaces)),
      obj => Json.fromFields(obj.toList.map((k, v) => k -> canonical(v)).sortBy(_._1))
    )
