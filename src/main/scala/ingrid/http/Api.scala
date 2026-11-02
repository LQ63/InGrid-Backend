package ingrid.http

import cats.effect.IO
import cats.syntax.semigroupk.*
import org.http4s.{HttpApp, HttpRoutes}
import org.http4s.server.Router
import ingrid.cohorts.CohortRoutes
import ingrid.commons.Store
import ingrid.events.EventRoutes
import ingrid.lecturers.LecturerRoutes
import ingrid.masterdata.MasterDataRoutes
import ingrid.plans.PlanRoutes
import ingrid.rooms.RoomRoutes
import ingrid.scheduling.{SchedulingRoutes, SchedulingService}

/** Komposition aller Slices zur API unter `/api/v1`. */
object Api:

  val basePath: String = "/api/v1"

  def routes(store: Store): HttpRoutes[IO] =
    List(
      new MasterDataRoutes(store).routes,
      new RoomRoutes(store).routes,
      new LecturerRoutes(store).routes,
      new CohortRoutes(store).routes,
      new PlanRoutes(store).routes,
      // Die Einplanungs-Routen liegen unter /events/... und müssen vor den
      // allgemeinen Event-Routen greifen.
      new SchedulingRoutes(new SchedulingService(store)).routes,
      new EventRoutes(store).routes
    ).reduce(_ <+> _)

  /** Gerouteter Baum ohne CORS – so auch im Test verwendet. */
  def app(store: Store): HttpApp[IO] =
    Responses.toApp(Router(basePath -> routes(store)))
