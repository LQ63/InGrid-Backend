package ingrid

import cats.effect.{ExitCode, IO, IOApp, Resource}
import com.comcast.ip4s.{Host, Port, host, port}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.{Router, Server}
import org.http4s.server.middleware.CORS
import org.slf4j.LoggerFactory
import ingrid.commons.Store
import ingrid.http.{Api, Responses}

/** Einstiegspunkt des Prototyp-Backends. Basis-URL `/api/v1`. */
object Main extends IOApp:

  private val log = LoggerFactory.getLogger(getClass)

  override def run(args: List[String]): IO[ExitCode] = serveForever(sys.env)

  /** Lädt die Demodaten und bedient Anfragen, bis der Prozess abgebrochen wird.
    *
    * Die Umgebung wird übergeben statt direkt gelesen, damit der Start testbar bleibt.
    */
  def serveForever(env: Map[String, String]): IO[ExitCode] =
    for
      store <- Store.seeded
      _     <- IO(log.info("Demodaten geladen"))
      code <- server(store, hostFrom(env), portFrom(env)).use { running =>
        IO(log.info(s"INgrid-Backend erreichbar unter ${running.baseUri}api/v1")) *>
          IO.never.as(ExitCode.Success)
      }
    yield code

  /** Der lauffähige Server – so auch im Test gestartet. */
  def server(store: Store, bindHost: Host, bindPort: Port): Resource[IO, Server] =
    EmberServerBuilder
      .default[IO]
      .withHost(bindHost)
      .withPort(bindPort)
      .withHttpApp(app(store))
      .build

  /** CORS ist offen, weil das Angular-Frontend im Prototyp auf einem anderen Port läuft. */
  private def app(store: Store) =
    Responses.toApp(CORS.policy.withAllowOriginAll(Router(Api.basePath -> Api.routes(store))))

  val defaultPort: Port = port"8080"
  val defaultHost: Host = host"0.0.0.0"

  /** `INGRID_PORT`, sonst 8080. Ein gesetzter, aber ungültiger Wert ist ein Fehler – stillschweigend
    * auf den Standard zurückzufallen würde die Fehlkonfiguration verschleiern.
    */
  def portFrom(env: Map[String, String]): Port =
    env.get("INGRID_PORT").fold(defaultPort) { raw =>
      Port.fromString(raw).getOrElse(configError("INGRID_PORT", raw, "Port 0–65535"))
    }

  /** `INGRID_HOST`, sonst 0.0.0.0. */
  def hostFrom(env: Map[String, String]): Host =
    env.get("INGRID_HOST").fold(defaultHost) { raw =>
      Host.fromString(raw).getOrElse(configError("INGRID_HOST", raw, "IP-Adresse oder Hostname"))
    }

  private def configError(name: String, raw: String, expected: String): Nothing =
    throw new IllegalArgumentException(s"$name='$raw' ist ungültig, erwartet: $expected")
