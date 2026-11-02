package ingrid.commons

/** Kern-Entitäten und Basistypen der Domäne.
  *
  * Laut ADR-0002 enthält `commons` ausschliesslich unveränderliche Stammdaten und übergreifende
  * Basistypen – keine Geschäftslogik. Die Konflikt- und Einplanungslogik liegt im Slice
  * `ingrid.scheduling`.
  */

enum RoomType(val code: String):
  case LectureHall extends RoomType("lecture_hall")
  case SeminarRoom extends RoomType("seminar_room")
  case PcPool      extends RoomType("pc_pool")
  case Lab         extends RoomType("lab")

enum EventType(val code: String, val short: String):
  case Lecture  extends EventType("lecture", "V")
  case Exercise extends EventType("exercise", "Ü")
  case Seminar  extends EventType("seminar", "S")
  case Lab      extends EventType("lab", "L")

enum PlanStatus(val code: String):
  case InProgress      extends PlanStatus("in_progress")
  case PendingApproval extends PlanStatus("pending_approval")

enum RoomAvailabilityStatus(val code: String):
  case Free       extends RoomAvailabilityStatus("free")
  case Occupied   extends RoomAvailabilityStatus("occupied")
  case TooSmall   extends RoomAvailabilityStatus("too_small")
  case Unsuitable extends RoomAvailabilityStatus("unsuitable")

enum EventStatusFilter(val code: String):
  case Placed   extends EventStatusFilter("placed")
  case Open     extends EventStatusFilter("open")
  case Conflict extends EventStatusFilter("conflict")

/** Die Reihenfolge der Cases bestimmt die Reihenfolge in `conflicts[]` (vgl. Mockdaten). */
enum ConflictCode(val code: String):
  case LecturerUnavailable  extends ConflictCode("LECTURER_UNAVAILABLE")
  case LecturerDoubleBooked extends ConflictCode("LECTURER_DOUBLE_BOOKED")
  case CohortOverlap        extends ConflictCode("COHORT_OVERLAP")
  case RoomDoubleBooked     extends ConflictCode("ROOM_DOUBLE_BOOKED")
  case RoomTooSmall         extends ConflictCode("ROOM_TOO_SMALL")
  case NoRoom               extends ConflictCode("NO_ROOM")

/** Auflösung eines Wire-Codes auf den Enum-Case. */
object Codes:
  def parse[A](values: Array[A], code: A => String)(s: String): Option[A] =
    values.find(a => code(a) == s)

  val roomType: String => Option[RoomType]    = parse(RoomType.values, _.code)
  val eventType: String => Option[EventType]  = parse(EventType.values, _.code)
  val planStatus: String => Option[PlanStatus] = parse(PlanStatus.values, _.code)
  val eventStatusFilter: String => Option[EventStatusFilter] =
    parse(EventStatusFilter.values, _.code)

// ---------------------------------------------------------------- Basistypen

/** `day` = 0–4 (Mo–Fr), `slot` = 0–5 (08:00–09:30 … 17:30–19:00). */
final case class DaySlot(day: Int, slot: Int)

object Grid:
  val days: Int                  = 5
  val slots: Int                 = 6
  val totalSlots: Int            = days * slots
  val dayNames: Vector[String]   = Vector("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag")
  val all: Vector[DaySlot]       = (0 until days).toVector.flatMap(d => (0 until slots).map(DaySlot(d, _)))
  def validDay(d: Int): Boolean  = d >= 0 && d < days
  def validSlot(s: Int): Boolean = s >= 0 && s < slots

// ---------------------------------------------------------------- Stammdaten

final case class Term(id: String, name: String, startDate: String, endDate: String)
final case class Timeslot(slot: Int, start: String, end: String)
final case class Program(id: String, name: String, abbreviation: String)

final case class Room(
    id: String,
    name: String,
    building: String,
    kind: RoomType,
    capacity: Int,
    equipment: List[String]
)

final case class Lecturer(
    id: String,
    name: String,
    shortName: String,
    department: String,
    unavailable: List[DaySlot]
)

// ------------------------------------------------------------- Planungsdaten

final case class Cohort(id: String, programId: String, semester: Int, termId: String, students: Int)

final case class Plan(id: String, cohortId: String, termId: String, status: PlanStatus, updatedAt: String)

final case class Placement(day: Int, slot: Int, roomId: Option[String])

final case class Event(
    id: String,
    title: String,
    kind: EventType,
    lecturerId: String,
    cohortId: String,
    expectedParticipants: Int,
    placement: Option[Placement]
)
