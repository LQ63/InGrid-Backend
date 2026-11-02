package ingrid.lecturers

import cats.effect.IO
import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityEncoder.given
import org.http4s.dsl.io.*
import ingrid.commons.*
import ingrid.commons.Json.given
import ingrid.http.Params.*
import ingrid.scheduling.Projections

final case class LecturerDto(
    id: String,
    name: String,
    shortName: String,
    department: String,
    eventCount: Int,
    unavailable: List[DaySlot]
)
object LecturerDto:
  given Encoder[LecturerDto] = deriveEncoder

  def of(s: Snapshot, l: Lecturer, termId: Option[String]): LecturerDto =
    LecturerDto(
      id = l.id,
      name = l.name,
      shortName = l.shortName,
      department = l.department,
      eventCount = Projections.lecturerEventCount(s, l.id, termId),
      unavailable = l.unavailable
    )

/** Slice „Dozierende“: Liste mit Suche und Wochenplan inkl. Nicht-verfügbar-Zeiten. */
final class LecturerRoutes(store: Store):

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "lecturers" :? QParam(q) +& TermIdParam(termId) =>
      store
        .query { s =>
          s.lecturers
            .filter(l => Search.matches(q, l.name, l.shortName, l.department))
            .map(LecturerDto.of(s, _, termId))
        }
        .flatMap(Ok(_))

    case GET -> Root / "lecturers" / lecturerId / "schedule" :? TermIdParam(termId) =>
      store
        .query { s =>
          val lecturer = s.requireLecturer(lecturerId)
          val schedule = Projections.schedule(s, Projections.lecturerEvents(s, lecturerId, termId))
          LecturerScheduleDto(schedule.entries, lecturer.unavailable)
        }
        .flatMap(Ok(_))
  }
