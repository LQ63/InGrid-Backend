package ingrid

import cats.data.Kleisli
import cats.effect.{ExitCode, IO}
import cats.effect.unsafe.implicits.global
import com.comcast.ip4s.{Host, Port}
import io.circe.Json
import org.http4s.*
import org.http4s.circe.CirceEntityDecoder.given
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.implicits.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import ingrid.commons.{ApiError, Seed, Store}
import ingrid.http.Responses

/** Prüft Serverstart, CORS und das Laden der Demodaten – also alles, was `Main` verdrahtet. */
final class BootstrapSpec extends AnyWordSpec with Matchers:

  "Der Server" should {
    "starten und die API ausliefern" in {
      val result = Main
        .server(Store.seeded.unsafeRunSync(), Host.fromString("127.0.0.1").get, Port.fromInt(0).get)
        .use { server =>
          EmberClientBuilder.default[IO].build.use { client =>
            val base = server.baseUri / "api" / "v1"
            for
              stats <- client.expect[Json](base / "stats")
              terms <- client.expect[Json](base / "terms")
            yield (stats, terms)
          }
        }
        .unsafeRunSync()

      result._1.hcursor.get[Int]("rooms").toOption shouldBe Some(13)
      result._2.asArray.map(_.size) shouldBe Some(2)
    }

    "CORS-Header für das Frontend setzen" in {
      val status = Main
        .server(Store.seeded.unsafeRunSync(), Host.fromString("127.0.0.1").get, Port.fromInt(0).get)
        .use { server =>
          EmberClientBuilder.default[IO].build.use { client =>
            val req = Request[IO](
              Method.GET,
              server.baseUri / "api" / "v1" / "terms",
              headers = Headers("Origin" -> "http://localhost:4200")
            )
            client.run(req).use { res =>
              IO.pure(res.headers.get(org.typelevel.ci.CIString("Access-Control-Allow-Origin")).map(_.head.value))
            }
          }
        }
        .unsafeRunSync()

      status shouldBe Some("*")
    }
  }

  "Der Einstiegspunkt" should {
    "die Demodaten laden und bedienen, bis er abgebrochen wird" in {
      import scala.concurrent.duration.*

      // Port 0 = freier Port vom Betriebssystem, damit der Test keinen belegten Port braucht.
      val env = Map("INGRID_HOST" -> "127.0.0.1", "INGRID_PORT" -> "0")
      val exit = Main
        .serveForever(env)
        .timeoutTo(2.seconds, IO.pure(ExitCode.Error))
        .unsafeRunSync()

      // Der Server läuft weiter, bis abgebrochen wird – der Timeout greift, kein vorzeitiges Ende.
      exit shouldBe ExitCode.Error
    }
  }

  "Die Konfiguration" should {
    "ohne Umgebungsvariablen auf 0.0.0.0:8080 zurückfallen" in {
      Main.portFrom(Map.empty).value shouldBe 8080
      Main.hostFrom(Map.empty).toString shouldBe "0.0.0.0"
    }

    "gesetzte Werte übernehmen" in {
      Main.portFrom(Map("INGRID_PORT" -> "9090")).value shouldBe 9090
      Main.hostFrom(Map("INGRID_HOST" -> "127.0.0.1")).toString shouldBe "127.0.0.1"
    }

    "andere Variablen ignorieren" in {
      Main.portFrom(Map("PORT" -> "9090")).value shouldBe 8080
    }

    "einen gesetzten, aber ungültigen Wert nicht stillschweigend ersetzen" in {
      for raw <- List("abc", "-1", "70000", "") do
        withClue(s"INGRID_PORT='$raw': ") {
          val error = the[IllegalArgumentException] thrownBy Main.portFrom(Map("INGRID_PORT" -> raw))
          error.getMessage should include("INGRID_PORT")
        }
      val hostError = the[IllegalArgumentException] thrownBy Main.hostFrom(Map("INGRID_HOST" -> "kein host!"))
      hostError.getMessage should include("INGRID_HOST")
    }
  }

  "Das Laden der Demodaten" should {
    "bei fehlender Ressource klar scheitern" in {
      val error = the[IllegalStateException] thrownBy Seed.load("/seed/gibtsnicht.json").unsafeRunSync()
      error.getMessage should include("/seed/gibtsnicht.json")
    }

    "bei unlesbarem Datensatz klar scheitern" in {
      val error = the[IllegalStateException] thrownBy Seed.load("/seed/kaputt.json").unsafeRunSync()
      error.getMessage should include("kaputt.json")
    }
  }

  "Die Fehlerbehandlung" should {
    "unerwartete Ausnahmen als 500 mit JSON melden" in {
      val boom: HttpRoutes[IO] =
        Kleisli(_ => cats.data.OptionT.liftF(IO.raiseError[Response[IO]](new RuntimeException("geplatzt"))))
      val app = Responses.toApp(boom)

      val res  = app.run(Request[IO](Method.GET, uri"/irgendwas")).unsafeRunSync()
      val body = res.as[Json].unsafeRunSync()

      res.status shouldBe Status.InternalServerError
      body.hcursor.get[String]("code").toOption shouldBe Some("INTERNAL_ERROR")
      body.hcursor.get[String]("message").toOption.value should not be empty
    }

    "einen ApiError mit unmöglichem Status auf 500 abbilden" in {
      val weird: HttpRoutes[IO] =
        Kleisli(_ => cats.data.OptionT.liftF(IO.raiseError[Response[IO]](ApiError(999, "X", "kaputt"))))
      val res = Responses.toApp(weird).run(Request[IO](Method.GET, uri"/irgendwas")).unsafeRunSync()
      res.status shouldBe Status.InternalServerError
    }
  }

  extension [A](o: Option[A]) private def value: A = o.getOrElse(fail("Wert erwartet"))
