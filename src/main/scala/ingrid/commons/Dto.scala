package ingrid.commons

import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder
import ingrid.commons.Json.given

/** Wire-DTOs, die mehrere Slices gemeinsam ausliefern (Referenzen, Konflikte, Kalender).
  * Enthalten keine Logik – reine Datenträger.
  */

final case class ErrorDto(code: String, message: String)
object ErrorDto:
  given Encoder[ErrorDto] = deriveEncoder

final case class LecturerRefDto(id: String, name: String, shortName: String)
object LecturerRefDto:
  given Encoder[LecturerRefDto]      = deriveEncoder
  def of(l: Lecturer): LecturerRefDto = LecturerRefDto(l.id, l.name, l.shortName)

final case class CohortRefDto(id: String, program: Program, semester: Int)
object CohortRefDto:
  given Encoder[CohortRefDto] = deriveEncoder

final case class TermRefDto(id: String, name: String)
object TermRefDto:
  given Encoder[TermRefDto]  = deriveEncoder
  def of(t: Term): TermRefDto = TermRefDto(t.id, t.name)

final case class ConflictDto(code: ConflictCode, message: String, relatedEventId: Option[String])
object ConflictDto:
  given Encoder[ConflictDto] = deriveEncoder

final case class PlacementDto(day: Int, slot: Int, roomId: Option[String])
object PlacementDto:
  given Encoder[PlacementDto]           = deriveEncoder
  def of(p: Placement): PlacementDto    = PlacementDto(p.day, p.slot, p.roomId)

final case class EventDto(
    id: String,
    title: String,
    `type`: EventType,
    lecturer: LecturerRefDto,
    cohortId: String,
    expectedParticipants: Int,
    placement: Option[PlacementDto],
    conflicts: List[ConflictDto]
)
object EventDto:
  given Encoder[EventDto] = deriveEncoder

final case class ScheduleEntryDto(
    eventId: String,
    title: String,
    `type`: EventType,
    day: Int,
    slot: Int,
    roomId: Option[String],
    lecturer: LecturerRefDto,
    cohort: CohortRefDto,
    conflicts: List[ConflictDto]
)
object ScheduleEntryDto:
  given Encoder[ScheduleEntryDto] = deriveEncoder

final case class ScheduleDto(entries: List[ScheduleEntryDto])
object ScheduleDto:
  given Encoder[ScheduleDto] = deriveEncoder

final case class LecturerScheduleDto(entries: List[ScheduleEntryDto], unavailable: List[DaySlot])
object LecturerScheduleDto:
  given Encoder[LecturerScheduleDto] = deriveEncoder
