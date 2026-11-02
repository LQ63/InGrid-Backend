package ingrid.plans

import cats.effect.IO
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityDecoder.given
import org.http4s.circe.CirceEntityEncoder.given
import org.http4s.dsl.io.*
import ingrid.commons.*
import ingrid.commons.Json.given
import ingrid.events.EventIds
import ingrid.http.Params
import ingrid.http.Params.*
import ingrid.scheduling.Projections

final case class PlanDto(
    id: String,
    cohort: CohortRefDto,
    term: TermRefDto,
    status: PlanStatus,
    updatedAt: String,
    placedCount: Int,
    eventCount: Int,
    conflictCount: Int
)
object PlanDto:
  given Encoder[PlanDto] = deriveEncoder

  def of(s: Snapshot, p: Plan): PlanDto =
    val events = Projections.cohortEvents(s, p.cohortId)
    PlanDto(
      id = p.id,
      cohort = Projections.cohortRef(s, p.cohortId),
      term = TermRefDto.of(s.requireTerm(p.termId)),
      status = p.status,
      updatedAt = p.updatedAt,
      placedCount = events.count(Projections.isPlaced),
      eventCount = events.size,
      conflictCount = events.count(Projections.hasConflict(s, _))
    )

final case class PlanCreate(termId: String, cohortId: String, copyFromCatalog: Option[Boolean])
object PlanCreate:
  given Decoder[PlanCreate] = deriveDecoder

final case class PlanCreatedDto(id: String)
object PlanCreatedDto:
  given Encoder[PlanCreatedDto] = deriveEncoder

/** Slice „Pläne“: Übersicht der Pläne in Bearbeitung, Anlegen und Öffnen. */
final class PlanRoutes(store: Store):

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "plans" :? TermIdParam(termId) +& PlanStatusParam(statusParam) =>
      store
        .query { s =>
          val status = Params.opt(statusParam, "status")
          s.plans
            .filter(p => termId.forall(_ == p.termId))
            .filter(p => status.forall(_ == p.status))
            .sortBy(_.updatedAt)
            .reverse
            .map(PlanDto.of(s, _))
        }
        .flatMap(Ok(_))

    case req @ POST -> Root / "plans" =>
      for
        body <- req.as[PlanCreate]
        dto  <- create(body)
        res  <- Created(dto)
      yield res

    case GET -> Root / "plans" / planId =>
      store.query(s => PlanDto.of(s, s.requirePlan(planId))).flatMap(Ok(_))

    case GET -> Root / "plans" / planId / "events" =>
      store
        .query { s =>
          val plan = s.requirePlan(planId)
          Projections.events(s, Projections.cohortEvents(s, plan.cohortId))
        }
        .flatMap(Ok(_))
  }

  private def create(body: PlanCreate): IO[PlanCreatedDto] =
    store.mutate { s =>
      val cohort = s.requireCohort(body.cohortId)
      s.requireTerm(body.termId)
      if cohort.termId != body.termId then
        throw ApiError.badRequest(
          s"Semester ${cohort.id} gehört zu ${cohort.termId}, nicht zu ${body.termId}"
        )
      s.planByCohortId.get(cohort.id).foreach { existing =>
        throw ApiError.conflict(s"Für ${cohort.id} existiert bereits Plan ${existing.id}")
      }

      val plan = Plan(
        id = nextPlanId(s),
        cohortId = cohort.id,
        termId = body.termId,
        status = PlanStatus.InProgress,
        updatedAt = Clock.nowIso
      )
      val copied = if body.copyFromCatalog.getOrElse(true) then rollover(s, cohort) else Nil
      val next   = s.withPlans(s.plans :+ plan).withEvents(s.events ++ copied)
      (next, PlanCreatedDto(plan.id))
    }

  /** Übernahme der Veranstaltungen („X Veranstaltungen werden übernommen“).
    *
    * Hat das Semester schon Veranstaltungen, sind das die zu übernehmenden – es wird nichts
    * kopiert. Ist es neu und leer, dient das gleiche Fachsemester desselben Studiengangs aus einem
    * anderen Zeitraum als Schablone (Vorjahres-Rollover, vgl. `semester_rollover.feature`):
    * Titel, Typ, Dozent:in und Teilnehmerzahl werden übernommen, die Platzierung als Ausgangsbasis
    * ebenfalls.
    */
  private def rollover(s: Snapshot, cohort: Cohort): List[Event] =
    if Projections.cohortEvents(s, cohort.id).nonEmpty then Nil
    else
      val template = s.cohorts
        .filter(c =>
          c.id != cohort.id && c.programId == cohort.programId && c.semester == cohort.semester
        )
        .flatMap(c => Projections.cohortEvents(s, c.id))

      val startId = EventIds.next(s)
      template.zipWithIndex.map { case (e, i) =>
        e.copy(id = s"e${startId + i}", cohortId = cohort.id)
      }

  /** `p<n>`; bereits vergebene Nummern werden übersprungen, da Pläne gelöscht werden könnten. */
  private def nextPlanId(s: Snapshot): String =
    Iterator.from(s.plans.size + 1).map(n => s"p$n").find(!s.planById.contains(_)).get
