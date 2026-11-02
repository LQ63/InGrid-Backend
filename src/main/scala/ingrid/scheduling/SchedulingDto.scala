package ingrid.scheduling

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import ingrid.commons.*

/** Requests und Responses des Scheduling-Slices. */

final case class PlacementRequest(day: Int, slot: Int, roomId: Option[String])
object PlacementRequest:
  given Decoder[PlacementRequest] = deriveDecoder

final case class PlacementCheckRequest(eventId: String, termId: String)
object PlacementCheckRequest:
  given Decoder[PlacementCheckRequest] = deriveDecoder

final case class PlacementCheckResultDto(
    day: Int,
    slot: Int,
    suggestedRoomId: Option[String],
    conflicts: List[ConflictDto]
)
object PlacementCheckResultDto:
  given Encoder[PlacementCheckResultDto] = deriveEncoder
