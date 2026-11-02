package ingrid.commons

import cats.effect.IO
import io.circe.{Decoder, HCursor}
import io.circe.parser.decode
import scala.io.{Codec, Source}
import ingrid.commons.Json.given

/** Lädt den Demodatensatz aus `raumplanung-mockdata.json`.
  *
  * Die Datei ist in API-Form aufgebaut (verschachtelte Referenzen, vorberechnete Zähler und
  * `conflicts[]`). Beim Einlesen wird auf die Domänenform abgebildet: Referenzen werden auf IDs
  * reduziert, alle abgeleiteten Felder werden verworfen und zur Laufzeit neu berechnet.
  */
object Seed:

  val defaultResource = "/seed/raumplanung-mockdata.json"

  def load: IO[Snapshot] = load(defaultResource)

  def load(resource: String): IO[Snapshot] =
    for
      raw <- readResource(resource)
      snapshot <- IO.fromEither(
        decode[Snapshot](raw)(using snapshotDecoder).left.map(e =>
          new IllegalStateException(s"Demodaten $resource konnten nicht gelesen werden: ${e.getMessage}", e)
        )
      )
    yield snapshot

  private def readResource(path: String): IO[String] =
    IO.blocking {
      val stream = Option(getClass.getResourceAsStream(path))
        .getOrElse(throw new IllegalStateException(s"Ressource $path nicht gefunden"))
      try Source.fromInputStream(stream)(using Codec.UTF8).mkString
      finally stream.close()
    }

  private val roomDecoder: Decoder[Room] = (c: HCursor) =>
    for
      id        <- c.get[String]("id")
      name      <- c.get[String]("name")
      building  <- c.get[String]("building")
      kind      <- c.get[RoomType]("type")
      capacity  <- c.get[Int]("capacity")
      equipment <- c.getOrElse[List[String]]("equipment")(Nil)
    yield Room(id, name, building, kind, capacity, equipment)

  private val lecturerDecoder: Decoder[Lecturer] = (c: HCursor) =>
    for
      id          <- c.get[String]("id")
      name        <- c.get[String]("name")
      shortName   <- c.get[String]("shortName")
      department  <- c.get[String]("department")
      unavailable <- c.getOrElse[List[DaySlot]]("unavailable")(Nil)
    yield Lecturer(id, name, shortName, department, unavailable)

  private val cohortDecoder: Decoder[Cohort] = (c: HCursor) =>
    for
      id        <- c.get[String]("id")
      programId <- c.downField("program").get[String]("id")
      semester  <- c.get[Int]("semester")
      termId    <- c.get[String]("termId")
      students  <- c.get[Int]("students")
    yield Cohort(id, programId, semester, termId, students)

  private val planDecoder: Decoder[Plan] = (c: HCursor) =>
    for
      id        <- c.get[String]("id")
      cohortId  <- c.downField("cohort").get[String]("id")
      termId    <- c.downField("term").get[String]("id")
      status    <- c.get[PlanStatus]("status")
      updatedAt <- c.get[String]("updatedAt")
    yield Plan(id, cohortId, termId, status, updatedAt)

  private given Decoder[Placement] = (c: HCursor) =>
    for
      day    <- c.get[Int]("day")
      slot   <- c.get[Int]("slot")
      roomId <- c.get[Option[String]]("roomId")
    yield Placement(day, slot, roomId)

  private val eventDecoder: Decoder[Event] = (c: HCursor) =>
    for
      id         <- c.get[String]("id")
      title      <- c.get[String]("title")
      kind       <- c.get[EventType]("type")
      lecturerId <- c.downField("lecturer").get[String]("id")
      cohortId   <- c.get[String]("cohortId")
      expected   <- c.get[Int]("expectedParticipants")
      placement  <- c.get[Option[Placement]]("placement")
    yield Event(id, title, kind, lecturerId, cohortId, expected, placement)

  private val termDecoder: Decoder[Term]         = Decoder.forProduct4("id", "name", "startDate", "endDate")(Term.apply)
  private val timeslotDecoder: Decoder[Timeslot] = Decoder.forProduct3("slot", "start", "end")(Timeslot.apply)

  private val snapshotDecoder: Decoder[Snapshot] = (c: HCursor) =>
    for
      terms     <- c.get[List[Term]]("terms")(using Decoder.decodeList(using termDecoder))
      timeslots <- c.get[List[Timeslot]]("timeslots")(using Decoder.decodeList(using timeslotDecoder))
      programs  <- c.get[List[Program]]("programs")
      rooms     <- c.get[List[Room]]("rooms")(using Decoder.decodeList(using roomDecoder))
      lecturers <- c.get[List[Lecturer]]("lecturers")(using Decoder.decodeList(using lecturerDecoder))
      cohorts   <- c.get[List[Cohort]]("cohorts")(using Decoder.decodeList(using cohortDecoder))
      plans     <- c.get[List[Plan]]("plans")(using Decoder.decodeList(using planDecoder))
      events    <- c.get[List[Event]]("events")(using Decoder.decodeList(using eventDecoder))
    yield Snapshot(terms, timeslots, programs, rooms, lecturers, cohorts, plans, events)
