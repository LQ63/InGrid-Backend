package ingrid.commons

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}

/** Wire-Codecs für die Basistypen. Slice-eigene DTOs bringen ihre Codecs im Companion mit. */
object Json:

  private def enumEncoder[A](code: A => String): Encoder[A] =
    Encoder.encodeString.contramap(code)

  private def enumDecoder[A](parse: String => Option[A], name: String): Decoder[A] =
    Decoder.decodeString.emap(s => parse(s).toRight(s"Unbekannter Wert für $name: $s"))

  given Encoder[RoomType] = enumEncoder(_.code)
  given Decoder[RoomType] = enumDecoder(Codes.roomType, "type")

  given Encoder[EventType] = enumEncoder(_.code)
  given Decoder[EventType] = enumDecoder(Codes.eventType, "type")

  given Encoder[PlanStatus] = enumEncoder(_.code)
  given Decoder[PlanStatus] = enumDecoder(Codes.planStatus, "status")

  given Encoder[RoomAvailabilityStatus] = enumEncoder(_.code)
  given Encoder[ConflictCode]           = enumEncoder(_.code)

  given Encoder[DaySlot]  = deriveEncoder
  given Decoder[DaySlot]  = deriveDecoder
  given Encoder[Term]     = deriveEncoder
  given Encoder[Timeslot] = deriveEncoder
  given Encoder[Program]  = deriveEncoder
  given Decoder[Program]  = deriveDecoder
