package ingrid.masterdata

import cats.effect.IO
import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityEncoder.given
import org.http4s.dsl.io.*
import ingrid.commons.*
import ingrid.commons.Json.given
import ingrid.http.Params.*

/** Zahlen der Startseite. */
final case class StatsDto(rooms: Int, lecturers: Int, cohorts: Int, plansInProgress: Int)
object StatsDto:
  given Encoder[StatsDto] = deriveEncoder

/** Slice „Stammdaten“: Semesterzeiträume, Zeitraster, Studiengänge, Startseiten-Kennzahlen. */
final class MasterDataRoutes(store: Store):

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "terms" =>
      store.query(_.terms).flatMap(Ok(_))

    case GET -> Root / "timeslots" =>
      store.query(_.timeslots.sortBy(_.slot)).flatMap(Ok(_))

    case GET -> Root / "programs" =>
      store.query(_.programs).flatMap(Ok(_))

    case GET -> Root / "stats" :? TermIdParam(termId) =>
      store
        .query { s =>
          val cohorts = s.cohorts.filter(c => termId.forall(_ == c.termId))
          val plans   = s.plans.filter(p => termId.forall(_ == p.termId))
          StatsDto(
            rooms = s.rooms.size,
            lecturers = s.lecturers.size,
            cohorts = cohorts.size,
            plansInProgress = plans.size
          )
        }
        .flatMap(Ok(_))
  }
