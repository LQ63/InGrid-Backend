package ingrid.scheduling

import ingrid.commons.*

/** Abbildung des Domänenzustands auf die Wire-DTOs.
  *
  * Alle abgeleiteten Felder (`conflicts`, `usedSlots`, `placedCount`, `eventCount`,
  * `conflictCount`) werden hier aus dem aktuellen Zustand berechnet und nie gespeichert.
  */
object Projections:

  def lecturerRef(s: Snapshot, lecturerId: String): LecturerRefDto =
    LecturerRefDto.of(s.requireLecturer(lecturerId))

  def cohortRef(s: Snapshot, cohortId: String): CohortRefDto =
    val c = s.requireCohort(cohortId)
    CohortRefDto(c.id, s.requireProgram(c.programId), c.semester)

  def event(s: Snapshot, e: Event): EventDto =
    EventDto(
      id = e.id,
      title = e.title,
      `type` = e.kind,
      lecturer = lecturerRef(s, e.lecturerId),
      cohortId = e.cohortId,
      expectedParticipants = e.expectedParticipants,
      placement = e.placement.map(PlacementDto.of),
      conflicts = ConflictRules.conflictsOf(s, e)
    )

  def events(s: Snapshot, es: List[Event]): List[EventDto] =
    es.map(event(s, _))

  /** Kalendereinträge – nur eingeplante Veranstaltungen, stabil nach Tag/Slot sortiert. */
  def schedule(s: Snapshot, es: List[Event]): ScheduleDto =
    val entries =
      for
        e <- es
        p <- e.placement
      yield ScheduleEntryDto(
        eventId = e.id,
        title = e.title,
        `type` = e.kind,
        day = p.day,
        slot = p.slot,
        roomId = p.roomId,
        lecturer = lecturerRef(s, e.lecturerId),
        cohort = cohortRef(s, e.cohortId),
        conflicts = ConflictRules.conflictsOf(s, e)
      )
    ScheduleDto(entries.sortBy(x => (x.day, x.slot, x.eventId)))

  // -------------------------------------------------------------- Zählwerte

  def isPlaced(e: Event): Boolean = e.placement.isDefined

  def hasConflict(s: Snapshot, e: Event): Boolean = ConflictRules.conflictsOf(s, e).nonEmpty

  def cohortEvents(s: Snapshot, cohortId: String): List[Event] =
    s.eventsByCohortId.getOrElse(cohortId, Nil)

  /** Belegte Slots eines Raums in einem Semesterzeitraum (für den Belegungsbalken). */
  def usedSlots(s: Snapshot, roomId: String, termId: Option[String]): Int =
    s.events.count { e =>
      e.placement.exists(_.roomId.contains(roomId)) && termId.forall(t => s.termOf(e).contains(t))
    }

  def lecturerEventCount(s: Snapshot, lecturerId: String, termId: Option[String]): Int =
    s.events.count(e => e.lecturerId == lecturerId && termId.forall(t => s.termOf(e).contains(t)))

  /** Veranstaltungen eines Dozierenden, optional auf ein Semester eingeschränkt. */
  def lecturerEvents(s: Snapshot, lecturerId: String, termId: Option[String]): List[Event] =
    s.events.filter(e => e.lecturerId == lecturerId && termId.forall(t => s.termOf(e).contains(t)))

  def roomEvents(s: Snapshot, roomId: String, termId: Option[String]): List[Event] =
    s.events.filter { e =>
      e.placement.exists(_.roomId.contains(roomId)) && termId.forall(t => s.termOf(e).contains(t))
    }
