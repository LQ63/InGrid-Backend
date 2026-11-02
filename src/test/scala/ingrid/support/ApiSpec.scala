package ingrid.support

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityEncoder.given
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import ingrid.commons.{Snapshot, Store}
import ingrid.http.Api

/** Basis für Tests gegen die komplette API.
  *
  * Jeder Test bekommt über [[withApi]] einen frisch aus den Demodaten befüllten Store, damit
  * schreibende Tests sich nicht gegenseitig beeinflussen.
  */
trait ApiSpec extends AnyWordSpec with Matchers with OptionValues:

  final case class Res(status: Status, json: Json):
    def int(field: String): Int        = json.hcursor.get[Int](field).fold(throw _, identity)
    def str(field: String): String     = json.hcursor.get[String](field).fold(throw _, identity)
    def opt(field: String): Json       = json.hcursor.downField(field).focus.getOrElse(Json.Null)
    def items: Vector[Json]            = json.asArray.getOrElse(fail(s"Array erwartet, war: $json"))
    def ids: Vector[String]            = items.map(_.hcursor.get[String]("id").fold(throw _, identity))
    def codes: Vector[String]          =
      opt("conflicts").asArray.getOrElse(Vector.empty).map(_.hcursor.get[String]("code").fold(throw _, identity))

  final class Api4Test(app: HttpApp[IO]):
    def get(path: String): Res                 = run(Method.GET, path, None)
    def delete(path: String): Res              = run(Method.DELETE, path, None)
    def post(path: String, body: Json): Res    = run(Method.POST, path, Some(body))
    def put(path: String, body: Json): Res     = run(Method.PUT, path, Some(body))

    /** Schickt absichtlich fehlerhaftes JSON, um die Fehlerbehandlung zu prüfen. */
    def postRaw(path: String, raw: String): Res =
      val req = Request[IO](Method.POST, uri(path))
        .withEntity(raw)
        .withContentType(headers.`Content-Type`(MediaType.application.json))
      exec(req)

    private def run(method: Method, path: String, body: Option[Json]): Res =
      val base = Request[IO](method, uri(path))
      exec(body.fold(base)(b => base.withEntity(b)))

    private def exec(req: Request[IO]): Res =
      val res = app.run(req).unsafeRunSync()
      val raw = res.bodyText.compile.string.unsafeRunSync()
      val json =
        if raw.isEmpty then Json.Null
        else parse(raw).fold(e => fail(s"Antwort war kein JSON ($raw): $e"), identity)
      Res(res.status, json)

    private def uri(path: String): Uri =
      Uri.unsafeFromString(Api.basePath + path)

  /** Führt den Test mit frischem Zustand aus. */
  def withApi[A](f: Api4Test => A): A =
    f(new Api4Test(Api.app(Store.seeded.unsafeRunSync())))

  /** Für Tests, die zusätzlich direkt auf den Zustand schauen. */
  def withStore[A](f: (Store, Api4Test) => A): A =
    val store = Store.seeded.unsafeRunSync()
    f(store, new Api4Test(Api.app(store)))

  def snapshot: Snapshot = Store.seeded.unsafeRunSync().read.unsafeRunSync()

  def jsonOf(fields: (String, Json)*): Json = Json.obj(fields*)

  extension (s: String) def j: Json  = s.asJson
  extension (i: Int) def j: Json     = i.asJson
  extension (b: Boolean) def j: Json = b.asJson

object ApiSpec:
  /** Unveränderter Demodaten-Zustand für Tests der reinen Regeln. */
  lazy val snapshotForUnitTests: Snapshot =
    Store.seeded.unsafeRunSync().read.unsafeRunSync()
