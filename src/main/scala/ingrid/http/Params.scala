package ingrid.http

import cats.data.{Validated, ValidatedNel}
import org.http4s.{ParseFailure, QueryParamDecoder}
import org.http4s.dsl.impl.{
  OptionalQueryParamDecoderMatcher,
  OptionalValidatingQueryParamDecoderMatcher
}
import ingrid.commons.*

/** Query-Parameter der API.
  *
  * Werte, die fehlschlagen können (Zahlen, Enums), werden *validierend* gelesen. Sonst würde ein
  * ungültiger Wert dazu führen, dass die Route nicht greift und die Antwort ein irreführendes 404
  * wäre – richtig ist 400.
  */
object Params:

  private def coded[A](parse: String => Option[A]): QueryParamDecoder[A] =
    QueryParamDecoder[String].emap(s => parse(s).toRight(ParseFailure(s, s)))

  private given QueryParamDecoder[RoomType]          = coded(Codes.roomType)
  private given QueryParamDecoder[EventType]         = coded(Codes.eventType)
  private given QueryParamDecoder[PlanStatus]        = coded(Codes.planStatus)
  private given QueryParamDecoder[EventStatusFilter] = coded(Codes.eventStatusFilter)

  /** Optionaler Parameter: fehlt er, ist das in Ordnung; ist er ungültig, ist es ein 400. */
  def opt[A](value: Option[ValidatedNel[ParseFailure, A]], name: String): Option[A] =
    value match
      case None                          => None
      case Some(Validated.Valid(a))      => Some(a)
      case Some(Validated.Invalid(errs)) => throw ApiError.badRequest(message(name, errs))

  /** Pflicht-Parameter: fehlt er oder ist er ungültig, ist es ein 400. */
  def required[A](value: Option[ValidatedNel[ParseFailure, A]], name: String): A =
    value match
      case None                          => throw ApiError.badRequest(s"Parameter $name fehlt")
      case Some(Validated.Valid(a))      => a
      case Some(Validated.Invalid(errs)) => throw ApiError.badRequest(message(name, errs))

  def required(value: Option[String], name: String): String =
    value.getOrElse(throw ApiError.badRequest(s"Parameter $name fehlt"))

  private def message(name: String, errs: cats.data.NonEmptyList[ParseFailure]): String =
    s"Ungültiger Wert für $name: ${errs.head.sanitized}"

  // Freitext und IDs – können beim Lesen nicht fehlschlagen.
  object QParam          extends OptionalQueryParamDecoderMatcher[String]("q")
  object TermIdParam     extends OptionalQueryParamDecoderMatcher[String]("termId")
  object BuildingParam   extends OptionalQueryParamDecoderMatcher[String]("building")
  object ProgramIdParam  extends OptionalQueryParamDecoderMatcher[String]("programId")
  object LecturerIdParam extends OptionalQueryParamDecoderMatcher[String]("lecturerId")
  object EventIdParam    extends OptionalQueryParamDecoderMatcher[String]("eventId")

  // Zahlen und Enums – ungültige Werte müssen zu 400 führen.
  object MinCapacityParam extends OptionalValidatingQueryParamDecoderMatcher[Int]("minCapacity")
  object SemesterParam    extends OptionalValidatingQueryParamDecoderMatcher[Int]("semester")
  object DayParam         extends OptionalValidatingQueryParamDecoderMatcher[Int]("day")
  object SlotParam        extends OptionalValidatingQueryParamDecoderMatcher[Int]("slot")

  object RoomTypeParam    extends OptionalValidatingQueryParamDecoderMatcher[RoomType]("type")
  object EventTypeParam   extends OptionalValidatingQueryParamDecoderMatcher[EventType]("type")
  object PlanStatusParam  extends OptionalValidatingQueryParamDecoderMatcher[PlanStatus]("status")
  object EventStatusParam extends OptionalValidatingQueryParamDecoderMatcher[EventStatusFilter]("status")
