package ingrid.scheduling

import cats.effect.IO
import ingrid.commons.*

/** Anwendungsfälle der Einplanung: einplanen, ausplanen, Drag-&-Drop-Vorschau. */
final class SchedulingService(store: Store):

  /** `PUT /events/{id}/placement` – einplanen oder verschieben.
    *
    * Konflikte verhindern das Speichern nicht; sie stehen in der Antwort.
    */
  def place(eventId: String, req: PlacementRequest): IO[EventDto] =
    store.mutate { s =>
      val event = s.requireEvent(eventId)
      validateSlot(req.day, req.slot)

      val roomId = req.roomId match
        case Some(id) =>
          // Explizit gewählter Raum wird übernommen, auch wenn er Konflikte erzeugt.
          s.requireRoom(id)
          Some(id)
        case None =>
          // Ohne Angabe: kleinster passender freier Raum (ggf. keiner -> NO_ROOM).
          ConflictRules.chooseRoom(s, event, req.day, req.slot)

      val placed = event.copy(placement = Some(Placement(req.day, req.slot, roomId)))
      val next   = touchPlan(s.replaceEvent(placed), placed.cohortId)
      (next, Projections.event(next, placed))
    }

  /** `DELETE /events/{id}/placement` – zurück in die Leiste „Nicht eingeplant“. */
  def unplace(eventId: String): IO[EventDto] =
    store.mutate { s =>
      val event   = s.requireEvent(eventId)
      val cleared = event.copy(placement = None)
      val next    = touchPlan(s.replaceEvent(cleared), cleared.cohortId)
      (next, Projections.event(next, cleared))
    }

  /** `POST /placement-check` – Ergebnis für alle 30 Zellen auf einmal (Grün/Rot-Vorschau).
    *
    * Für jede Zelle wird der Raum vorgeschlagen, den `PUT .../placement` ohne `roomId` wählen
    * würde, und die Konflikte dieser hypothetischen Platzierung berechnet.
    */
  def check(req: PlacementCheckRequest): IO[List[PlacementCheckResultDto]] =
    store.query { s =>
      val event = s.requireEvent(req.eventId)
      s.termById.getOrElse(req.termId, throw ApiError.notFound(s"Semester ${req.termId} nicht gefunden"))

      Grid.all.toList.map { case DaySlot(day, slot) =>
        val suggested = ConflictRules.chooseRoom(s, event, day, slot)
        val candidate = Placement(day, slot, suggested)
        PlacementCheckResultDto(
          day = day,
          slot = slot,
          suggestedRoomId = suggested,
          conflicts = ConflictRules.conflicts(s, event, candidate)
        )
      }
    }

  private def validateSlot(day: Int, slot: Int): Unit =
    if !Grid.validDay(day) then throw ApiError.badRequest("day muss zwischen 0 und 4 liegen")
    if !Grid.validSlot(slot) then throw ApiError.badRequest("slot muss zwischen 0 und 5 liegen")

  /** Hält `updatedAt` des betroffenen Plans aktuell. */
  private def touchPlan(s: Snapshot, cohortId: String): Snapshot =
    s.planByCohortId.get(cohortId) match
      case None => s
      case Some(plan) =>
        s.withPlans(s.plans.map { p =>
          if p.id == plan.id then p.copy(updatedAt = Clock.nowIso) else p
        })
