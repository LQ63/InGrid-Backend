package ingrid.events

import cats.effect.IO
import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityDecoder.given
import org.http4s.circe.CirceEntityEncoder.given
import org.http4s.dsl.io.*
import ingrid.commons.*
import ingrid.commons.Json.given
import ingrid.scheduling.Projections

/** Vergabe der Veranstaltungs-IDs im Schema der Demodaten. */
object EventIds:
  def next(s: Snapshot): Int =
    s.events.flatMap(_.id.stripPrefix("e").toIntOption).maxOption.fold(1)(_ + 1)

final case class EventCreate(
    title: String,
    `type`: EventType,
    lecturerId: String,
    cohortId: String,
    expectedParticipants: Int
)
object EventCreate:
  given Decoder[EventCreate] = deriveDecoder

/** Slice „Veranstaltungen“: Anlegen, Detailpanel und Löschen. */
final class EventRoutes(store: Store):

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case req @ POST -> Root / "events" =>
      for
        body <- req.as[EventCreate]
        dto  <- create(body)
        res  <- Created(dto)
      yield res

    case GET -> Root / "events" / eventId =>
      store.query(s => Projections.event(s, s.requireEvent(eventId))).flatMap(Ok(_))

    case DELETE -> Root / "events" / eventId =>
      delete(eventId) *> NoContent()
  }

  private def create(body: EventCreate): IO[EventDto] =
    store.mutate { s =>
      if body.title.trim.isEmpty then throw ApiError.badRequest("title darf nicht leer sein")
      if body.expectedParticipants < 0 then
        throw ApiError.badRequest("expectedParticipants darf nicht negativ sein")
      s.requireLecturer(body.lecturerId)
      s.requireCohort(body.cohortId)

      val event = Event(
        id = nextEventId(s),
        title = body.title.trim,
        kind = body.`type`,
        lecturerId = body.lecturerId,
        cohortId = body.cohortId,
        expectedParticipants = body.expectedParticipants,
        placement = None // Neue Veranstaltungen landen in „Nicht eingeplant“.
      )
      val next = touchPlan(s.withEvents(s.events :+ event), event.cohortId)
      (next, Projections.event(next, event))
    }

  private def delete(eventId: String): IO[Unit] =
    store.mutate { s =>
      val event = s.requireEvent(eventId)
      val next  = touchPlan(s.withEvents(s.events.filterNot(_.id == event.id)), event.cohortId)
      (next, ())
    }

  /** IDs folgen dem Schema der Demodaten (`e12`). Eine Nummer über dem bisherigen Maximum ist
    * immer frei, ein Kollisionscheck also nicht nötig.
    */
  private def nextEventId(s: Snapshot): String =
    s"e${EventIds.next(s)}"

  private def touchPlan(s: Snapshot, cohortId: String): Snapshot =
    s.planByCohortId.get(cohortId) match
      case None => s
      case Some(plan) =>
        s.withPlans(s.plans.map(p => if p.id == plan.id then p.copy(updatedAt = Clock.nowIso) else p))
