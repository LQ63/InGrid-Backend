package ingrid.http

import cats.data.Kleisli
import cats.effect.IO
import org.http4s.circe.CirceEntityEncoder.given
import org.http4s.{HttpApp, HttpRoutes, MessageFailure, Response, Status}
import org.slf4j.LoggerFactory
import ingrid.commons.{ApiError, ErrorDto}

/** Übersetzt fachliche Fehler in die `Error`-Struktur der API. */
object Responses:

  private val log = LoggerFactory.getLogger(getClass)

  private def errorResponse(status: Status, code: String, message: String): Response[IO] =
    Response[IO](status).withEntity(ErrorDto(code, message))

  /** Macht aus den Routen eine App, in der jede Antwort JSON liefert – auch der
    * Nicht-gefunden-Fall und fehlerhafte Request-Bodies.
    */
  def toApp(routes: HttpRoutes[IO]): HttpApp[IO] =
    Kleisli { req =>
      routes
        .run(req)
        .getOrElse(
          errorResponse(Status.NotFound, "NOT_FOUND", s"${req.method} ${req.uri.path} nicht gefunden")
        )
        .handleErrorWith {
          case e: ApiError =>
            IO.pure(
              errorResponse(Status.fromInt(e.status).getOrElse(Status.InternalServerError), e.code, e.message)
            )
          case e: MessageFailure =>
            IO.pure(errorResponse(Status.BadRequest, "VALIDATION_ERROR", e.message))
          case e =>
            IO(log.error(s"Unbehandelter Fehler bei ${req.method} ${req.uri}", e)) *>
              IO.pure(errorResponse(Status.InternalServerError, "INTERNAL_ERROR", "Unerwarteter Serverfehler"))
        }
    }
