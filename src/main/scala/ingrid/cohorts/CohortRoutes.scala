package ingrid.cohorts

import cats.effect.IO
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityDecoder.given
import org.http4s.circe.CirceEntityEncoder.given
import org.http4s.dsl.io.*
import ingrid.commons.*
import ingrid.commons.Json.given
import ingrid.http.Params
import ingrid.http.Params.*
import ingrid.scheduling.Projections

final case class CohortDto(
    id: String,
    program: Program,
    semester: Int,
    termId: String,
    students: Int,
    placedCount: Int,
    eventCount: Int,
    planId: Option[String]
)
object CohortDto:
  given Encoder[CohortDto] = deriveEncoder

  def of(s: Snapshot, c: Cohort): CohortDto =
    val events = Projections.cohortEvents(s, c.id)
    CohortDto(
      id = c.id,
      program = s.requireProgram(c.programId),
      semester = c.semester,
      termId = c.termId,
      students = c.students,
      placedCount = events.count(Projections.isPlaced),
      eventCount = events.size,
      planId = s.planByCohortId.get(c.id).map(_.id)
    )

final case class CohortCreate(programId: String, semester: Int, termId: String, students: Int)
object CohortCreate:
  given Decoder[CohortCreate] = deriveDecoder

/** Slice „Semester“ (Kohorten): Liste, Anlegen, Detail, Veranstaltungen und Wochenplan. */
final class CohortRoutes(store: Store):

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "cohorts" :? QParam(q)
        +& TermIdParam(termId)
        +& ProgramIdParam(programId)
        +& SemesterParam(semesterParam) =>
      store
        .query { s =>
          val semester = Params.opt(semesterParam, "semester")
          s.cohorts
            .filter(c => termId.forall(_ == c.termId))
            .filter(c => programId.forall(_ == c.programId))
            .filter(c => semester.forall(_ == c.semester))
            .filter { c =>
              val program = s.programById.get(c.programId)
              Search.matches(
                q,
                c.id,
                program.map(_.name).getOrElse(""),
                program.map(_.abbreviation).getOrElse(""),
                s"${program.map(_.abbreviation).getOrElse("")} ${c.semester}"
              )
            }
            .map(CohortDto.of(s, _))
        }
        .flatMap(Ok(_))

    case req @ POST -> Root / "cohorts" =>
      for
        body <- req.as[CohortCreate]
        dto  <- create(body)
        res  <- Created(dto)
      yield res

    case GET -> Root / "cohorts" / cohortId =>
      store.query(s => CohortDto.of(s, s.requireCohort(cohortId))).flatMap(Ok(_))

    case GET -> Root / "cohorts" / cohortId / "events" :? QParam(q)
        +& EventTypeParam(eventTypeParam)
        +& LecturerIdParam(lecturerId)
        +& DayParam(dayParam)
        +& EventStatusParam(statusParam) =>
      store
        .query { s =>
          val eventType = Params.opt(eventTypeParam, "type")
          val day       = Params.opt(dayParam, "day")
          val status    = Params.opt(statusParam, "status")
          s.requireCohort(cohortId)
          val events = Projections
            .cohortEvents(s, cohortId)
            .filter(e => eventType.forall(_ == e.kind))
            .filter(e => lecturerId.forall(_ == e.lecturerId))
            .filter(e => day.forall(d => e.placement.exists(_.day == d)))
            .filter { e =>
              val lecturer = s.lecturerById.get(e.lecturerId)
              Search.matches(q, e.title, lecturer.map(_.name).getOrElse(""))
            }
            .filter { e =>
              status.forall {
                case EventStatusFilter.Placed   => Projections.isPlaced(e)
                case EventStatusFilter.Open     => !Projections.isPlaced(e)
                case EventStatusFilter.Conflict => Projections.hasConflict(s, e)
              }
            }
          Projections.events(s, events)
        }
        .flatMap(Ok(_))

    case GET -> Root / "cohorts" / cohortId / "schedule" :? TermIdParam(_) =>
      store
        .query { s =>
          s.requireCohort(cohortId)
          Projections.schedule(s, Projections.cohortEvents(s, cohortId))
        }
        .flatMap(Ok(_))
  }

  /** Legt ein Semester an, sofern es die Kombination Studiengang + Fachsemester + Zeitraum
    * noch nicht gibt (sonst 409).
    */
  private def create(body: CohortCreate): IO[CohortDto] =
    store.mutate { s =>
      if body.semester < 1 then throw ApiError.badRequest("semester muss mindestens 1 sein")
      if body.students < 0 then throw ApiError.badRequest("students darf nicht negativ sein")
      val program = s.requireProgram(body.programId)
      s.termById.getOrElse(body.termId, throw ApiError.notFound(s"Semester ${body.termId} nicht gefunden"))

      val duplicate = s.cohorts.find(c =>
        c.programId == body.programId && c.semester == body.semester && c.termId == body.termId
      )
      duplicate.foreach { c =>
        throw ApiError.conflict(
          s"Semester ${program.abbreviation} ${c.semester} existiert in ${body.termId} bereits (${c.id})"
        )
      }

      val cohort = Cohort(
        id = nextId(s, body),
        programId = body.programId,
        semester = body.semester,
        termId = body.termId,
        students = body.students
      )
      val next = s.withCohorts(s.cohorts :+ cohort)
      (next, CohortDto.of(next, cohort))
    }

  /** IDs folgen dem Schema der Demodaten (`inf3` = Studiengang + Fachsemester). */
  private def nextId(s: Snapshot, body: CohortCreate): String =
    val base = s"${body.programId}${body.semester}"
    if !s.cohortById.contains(base) then base
    else Iterator.from(2).map(n => s"$base-$n").find(!s.cohortById.contains(_)).get
