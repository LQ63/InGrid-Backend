package ingrid.scheduling

import cats.effect.IO
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityDecoder.given
import org.http4s.circe.CirceEntityEncoder.given
import org.http4s.dsl.io.*

/** Slice „Einplanung“: Drag & Drop, Ausplanen und die Grün/Rot-Vorschau. */
final class SchedulingRoutes(service: SchedulingService):

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case req @ PUT -> Root / "events" / eventId / "placement" =>
      for
        body <- req.as[PlacementRequest]
        dto  <- service.place(eventId, body)
        res  <- Ok(dto)
      yield res

    case DELETE -> Root / "events" / eventId / "placement" =>
      service.unplace(eventId).flatMap(Ok(_))

    case req @ POST -> Root / "placement-check" =>
      for
        body    <- req.as[PlacementCheckRequest]
        results <- service.check(body)
        res     <- Ok(results)
      yield res
  }
