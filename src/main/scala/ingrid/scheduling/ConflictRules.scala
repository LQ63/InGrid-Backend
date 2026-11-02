package ingrid.scheduling

import ingrid.commons.*

/** Reine Regeln der Einplanung: Raumeignung, Raumwahl und Konflikterkennung.
  *
  * Das ist die öffentliche, seiteneffektfreie Schnittstelle des Scheduling-Slices; andere Slices
  * (Räume, Semester, Pläne) greifen ausschliesslich hierüber auf die Planungslogik zu.
  *
  * Grundsatz aus der Spezifikation: Konflikte sind *Soft Constraints*. Sie verhindern das Speichern
  * nie, sie werden nur gemeldet.
  */
object ConflictRules:

  /** `lab`-Veranstaltungen nur in `lab` oder `pc_pool`; alle anderen nicht in `lab`. */
  def suitable(room: Room, eventType: EventType): Boolean =
    eventType match
      case EventType.Lab => room.kind == RoomType.Lab || room.kind == RoomType.PcPool
      case _             => room.kind != RoomType.Lab

  def tooSmall(room: Room, expectedParticipants: Int): Boolean =
    room.capacity < expectedParticipants

  /** Veranstaltungen, die im selben Semesterzeitraum liegen – nur die können kollidieren. */
  private def sameTerm(s: Snapshot, event: Event): List[Event] =
    val term = s.termOf(event)
    s.events.filter(e => e.id != event.id && s.termOf(e) == term)

  /** Andere Veranstaltungen, die exakt auf (day, slot) liegen. */
  private def collidingAt(s: Snapshot, event: Event, day: Int, slot: Int): List[Event] =
    sameTerm(s, event).filter(_.placement.exists(p => p.day == day && p.slot == slot))

  /** Räume, die an (day, slot) bereits durch eine andere Veranstaltung belegt sind. */
  def occupiedRoomIds(s: Snapshot, event: Event, day: Int, slot: Int): Set[String] =
    collidingAt(s, event, day, slot).flatMap(_.placement.flatMap(_.roomId)).toSet

  /** Kleinster passender freier Raum.
    *
    * Ein bereits zugewiesener Raum bleibt erhalten, solange er weiterhin passt (frei, geeignet,
    * gross genug). Sonst wird der Raum mit der kleinsten ausreichenden Kapazität gewählt.
    */
  def chooseRoom(s: Snapshot, event: Event, day: Int, slot: Int): Option[String] =
    val occupied = occupiedRoomIds(s, event, day, slot)

    def fits(room: Room): Boolean =
      !occupied.contains(room.id) &&
        suitable(room, event.kind) &&
        !tooSmall(room, event.expectedParticipants)

    val keepCurrent = event.placement.flatMap(_.roomId).flatMap(s.roomById.get).filter(fits)

    keepCurrent
      .orElse(s.rooms.filter(fits).sortBy(r => (r.capacity, r.id)).headOption)
      .map(_.id)

  /** Konflikte einer konkreten Platzierung – in der Reihenfolge der `ConflictCode`-Cases. */
  def conflicts(s: Snapshot, event: Event, placement: Placement): List[ConflictDto] =
    val lecturer = s.lecturerById.get(event.lecturerId)
    val others   = collidingAt(s, event, placement.day, placement.slot)
    val room     = placement.roomId.flatMap(s.roomById.get)

    val unavailable =
      for
        l <- lecturer.toList
        if l.unavailable.contains(DaySlot(placement.day, placement.slot))
      yield ConflictDto(
        ConflictCode.LecturerUnavailable,
        s"${l.shortName} ist ${dayName(placement.day)} ${timeRange(s, placement.slot)} nicht verfügbar",
        None
      )

    val doubleBooked =
      for
        other <- others
        if other.lecturerId == event.lecturerId
        l     <- lecturer.toList
      yield ConflictDto(
        ConflictCode.LecturerDoubleBooked,
        s"${l.shortName} hält zeitgleich „${other.title}“ (${other.kind.short})",
        Some(other.id)
      )

    val cohortOverlap =
      for other <- others if other.cohortId == event.cohortId
      yield ConflictDto(
        ConflictCode.CohortOverlap,
        s"Überschneidung mit „${other.title}“ im selben Semester",
        Some(other.id)
      )

    val roomDoubleBooked =
      for
        r     <- room.toList
        other <- others
        if other.placement.flatMap(_.roomId).contains(r.id)
      yield ConflictDto(
        ConflictCode.RoomDoubleBooked,
        s"Raum ${r.name} ist zeitgleich durch „${other.title}“ belegt",
        Some(other.id)
      )

    val roomTooSmall =
      for r <- room.toList if tooSmall(r, event.expectedParticipants)
      yield ConflictDto(
        ConflictCode.RoomTooSmall,
        s"Raum ${r.name} zu klein (${r.capacity} Plätze, ${event.expectedParticipants} TN)",
        None
      )

    val noRoom =
      if placement.roomId.isEmpty then
        List(
          ConflictDto(
            ConflictCode.NoRoom,
            s"Kein passender freier Raum für ${event.expectedParticipants} TN verfügbar",
            None
          )
        )
      else Nil

    unavailable ++ doubleBooked ++ cohortOverlap ++ roomDoubleBooked ++ roomTooSmall ++ noRoom

  /** Konflikte einer Veranstaltung an ihrer aktuellen Position (leer, wenn nicht eingeplant). */
  def conflictsOf(s: Snapshot, event: Event): List[ConflictDto] =
    event.placement.fold(Nil)(conflicts(s, event, _))

  /** Status eines Raums für das Dropdown im Detailpanel.
    * Priorität bei mehreren Gründen: occupied > too_small > unsuitable.
    */
  def availabilityStatus(
      room: Room,
      event: Event,
      occupied: Set[String]
  ): RoomAvailabilityStatus =
    if occupied.contains(room.id) then RoomAvailabilityStatus.Occupied
    else if tooSmall(room, event.expectedParticipants) then RoomAvailabilityStatus.TooSmall
    else if !suitable(room, event.kind) then RoomAvailabilityStatus.Unsuitable
    else RoomAvailabilityStatus.Free

  private def dayName(day: Int): String =
    Grid.dayNames.lift(day).getOrElse(s"Tag $day")

  private def timeRange(s: Snapshot, slot: Int): String =
    s.timeslot(slot).fold(s"Slot $slot")(t => s"${t.start}–${t.end}")
