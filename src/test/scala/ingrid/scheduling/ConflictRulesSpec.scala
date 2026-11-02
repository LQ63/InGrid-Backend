package ingrid.scheduling

import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import ingrid.commons.*
import ingrid.support.ApiSpec

/** Unit-Tests der reinen Planungsregeln. */
final class ConflictRulesSpec extends AnyWordSpec with Matchers with OptionValues:

  private val base: Snapshot = ApiSpec.snapshotForUnitTests

  private def event(id: String): Event = base.requireEvent(id)
  private def room(id: String): Room   = base.requireRoom(id)

  "Raumeignung" should {
    "Labor-Veranstaltungen nur in Labor oder PC-Pool zulassen" in {
      ConflictRules.suitable(room("b-1.20"), EventType.Lab) shouldBe true // lab
      ConflictRules.suitable(room("b-0.08"), EventType.Lab) shouldBe true // pc_pool
      ConflictRules.suitable(room("a-1.02"), EventType.Lab) shouldBe false
      ConflictRules.suitable(room("a-2.15"), EventType.Lab) shouldBe false
    }

    "alle anderen Veranstaltungen nicht im Labor zulassen" in {
      for kind <- List(EventType.Lecture, EventType.Exercise, EventType.Seminar) do
        ConflictRules.suitable(room("b-1.20"), kind) shouldBe false
        ConflictRules.suitable(room("a-1.02"), kind) shouldBe true
        ConflictRules.suitable(room("b-0.08"), kind) shouldBe true
    }
  }

  "tooSmall" should {
    "nur bei zu geringer Kapazität greifen" in {
      ConflictRules.tooSmall(room("c-3.01"), 70) shouldBe true  // 60 Plätze
      ConflictRules.tooSmall(room("c-3.01"), 60) shouldBe false // genau passend
      ConflictRules.tooSmall(room("c-3.01"), 59) shouldBe false
    }
  }

  "Raumwahl ohne roomId" should {
    "den kleinsten passenden freien Raum nehmen" in {
      // e4: Übung, 35 TN -> b-0.08 (36) ist der kleinste geeignete Raum.
      ConflictRules.chooseRoom(base, event("e4"), 0, 0) shouldBe Some("b-0.08")
    }

    "auf den nächstgrösseren ausweichen, wenn der kleinste belegt ist" in {
      // e12 belegt b-0.08 am Mi/Slot 1 -> nächster passender ist a-2.14 (40).
      ConflictRules.chooseRoom(base, event("e4"), 2, 1) shouldBe Some("a-2.14")
    }

    "einen bereits zugewiesenen Raum behalten, solange er passt" in {
      val placed = event("e4").copy(placement = Some(Placement(0, 0, Some("a-1.02"))))
      // a-1.02 (150) ist viel zu gross, bleibt aber erhalten.
      ConflictRules.chooseRoom(base, placed, 1, 0) shouldBe Some("a-1.02")
    }

    "einen zugewiesenen Raum ersetzen, wenn er am Zielslot belegt ist" in {
      val placed = event("e4").copy(placement = Some(Placement(0, 0, Some("a-1.02"))))
      // Mo/Slot 1 ist a-1.02 durch e1 belegt -> kleinster freier passender Raum.
      ConflictRules.chooseRoom(base, placed, 0, 1) shouldBe Some("b-0.08")
    }

    "für Labore nur Labor/PC-Pool wählen" in {
      // e7: Labor, 20 TN -> b-1.20 (Labor, 20) ist kleinster geeigneter Raum.
      ConflictRules.chooseRoom(base, event("e7"), 0, 0) shouldBe Some("b-1.20")
    }

    "None liefern, wenn kein Raum gross genug ist" in {
      val huge = event("e4").copy(expectedParticipants = 5000, placement = None)
      ConflictRules.chooseRoom(base, huge, 0, 0) shouldBe None
    }

    "None liefern, wenn alle passenden Räume belegt sind" in {
      // Alle 13 Räume am Mo/Slot 0 belegen.
      val blockers = base.rooms.zipWithIndex.map { case (r, i) =>
        Event(s"block$i", s"Blocker $i", EventType.Lecture, "becker", "inf1", 1, Some(Placement(0, 0, Some(r.id))))
      }
      val blocked = base.withEvents(base.events ++ blockers)
      ConflictRules.chooseRoom(blocked, event("e4"), 0, 0) shouldBe None
    }
  }

  "Konflikterkennung" should {
    "LECTURER_UNAVAILABLE melden, inkl. Tag und Uhrzeit" in {
      // wolf ist Do (3) Slot 2 nicht verfügbar.
      val conflicts = ConflictRules.conflicts(base, event("e8"), Placement(3, 2, Some("a-1.02")))
      conflicts.map(_.code) shouldBe List(ConflictCode.LecturerUnavailable)
      conflicts.head.message shouldBe "Wolf ist Donnerstag 11:30–13:00 nicht verfügbar"
      conflicts.head.relatedEventId shouldBe None
    }

    "LECTURER_DOUBLE_BOOKED mit Bezug auf die andere Veranstaltung melden" in {
      // vogt hält e12 am Mi (2) Slot 1.
      val conflicts = ConflictRules.conflicts(base, event("e4"), Placement(2, 1, Some("a-2.14")))
      val conflict  = conflicts.find(_.code == ConflictCode.LecturerDoubleBooked).value
      conflict.message shouldBe "Vogt hält zeitgleich „Programmierung“ (Ü)"
      conflict.relatedEventId shouldBe Some("e12")
    }

    "COHORT_OVERLAP melden" in {
      val conflicts = ConflictRules.conflicts(base, event("e4"), Placement(0, 1, Some("b-0.08")))
      val conflict  = conflicts.find(_.code == ConflictCode.CohortOverlap).value
      conflict.message shouldBe "Überschneidung mit „Algorithmen und Datenstrukturen“ im selben Semester"
      conflict.relatedEventId shouldBe Some("e1")
    }

    "ROOM_DOUBLE_BOOKED melden" in {
      // a-1.02 ist am Mo (0) Slot 1 durch e1 belegt.
      val conflicts = ConflictRules.conflicts(base, event("e5"), Placement(0, 1, Some("a-1.02")))
      val conflict  = conflicts.find(_.code == ConflictCode.RoomDoubleBooked).value
      conflict.relatedEventId shouldBe Some("e1")
      conflict.message should include("A 1.02")
    }

    "ROOM_TOO_SMALL mit Kapazität und Teilnehmerzahl melden" in {
      val conflicts = ConflictRules.conflicts(base, event("e22"), Placement(2, 2, Some("c-3.01")))
      val conflict  = conflicts.find(_.code == ConflictCode.RoomTooSmall).value
      conflict.message shouldBe "Raum C 3.01 zu klein (60 Plätze, 70 TN)"
      conflict.relatedEventId shouldBe None
    }

    "NO_ROOM melden, wenn eingeplant ohne Raum" in {
      val conflicts = ConflictRules.conflicts(base, event("e4"), Placement(4, 5, None))
      conflicts.map(_.code) shouldBe List(ConflictCode.NoRoom)
      conflicts.head.message should include("35 TN")
    }

    "mehrere Konflikte in der Reihenfolge der Codes liefern" in {
      // e4 am Mi (2) Slot 3: vogt hält dort e2 (gleiche Kohorte inf3).
      val conflicts = ConflictRules.conflicts(base, event("e4"), Placement(2, 3, Some("b-0.08")))
      conflicts.map(_.code) shouldBe List(
        ConflictCode.LecturerDoubleBooked,
        ConflictCode.CohortOverlap
      )
    }

    "keine Konflikte bei freiem, passendem Slot liefern" in {
      ConflictRules.conflicts(base, event("e4"), Placement(4, 5, Some("b-0.08"))) shouldBe empty
    }

    "sich nicht selbst als Kollision zählen" in {
      // e1 an seiner eigenen Position ist konfliktfrei.
      ConflictRules.conflictsOf(base, event("e1")) shouldBe empty
    }

    "nicht eingeplante Veranstaltungen als konfliktfrei behandeln" in {
      ConflictRules.conflictsOf(base, event("e4")) shouldBe empty
    }

    "nur innerhalb desselben Semesterzeitraums kollidieren" in {
      // Kohorte in einem anderen Term, gleiche Person, gleicher Slot wie e1.
      val otherTerm = Cohort("inf3-ss", "inf", 3, "ss2027", 100)
      val otherEvent = Event(
        "e900",
        "Parallelkurs",
        EventType.Lecture,
        "becker",
        "inf3-ss",
        100,
        Some(Placement(0, 1, Some("a-1.02")))
      )
      val s = base.withCohorts(base.cohorts :+ otherTerm).withEvents(base.events :+ otherEvent)
      ConflictRules.conflictsOf(s, s.requireEvent("e900")) shouldBe empty
      ConflictRules.conflictsOf(s, s.requireEvent("e1")) shouldBe empty
    }
  }

  "Raumstatus für das Detailpanel" should {
    "Priorität occupied > too_small > unsuitable beachten" in {
      val labEvent = event("e7") // Labor, 20 TN

      // belegt schlägt alles
      ConflictRules.availabilityStatus(room("a-2.15"), labEvent, Set("a-2.15")) shouldBe
        RoomAvailabilityStatus.Occupied
      // zu klein schlägt ungeeignet (a-2.16 = Seminarraum mit 24 Plätzen -> passt)
      ConflictRules.availabilityStatus(room("b-1.21"), event("e4"), Set.empty) shouldBe
        RoomAvailabilityStatus.TooSmall
      // ungeeignet: Seminarraum für ein Labor, aber gross genug
      ConflictRules.availabilityStatus(room("a-2.15"), labEvent, Set.empty) shouldBe
        RoomAvailabilityStatus.Unsuitable
      // frei
      ConflictRules.availabilityStatus(room("b-1.20"), labEvent, Set.empty) shouldBe
        RoomAvailabilityStatus.Free
    }
  }

  "occupiedRoomIds" should {
    "die Räume der anderen Veranstaltungen im selben Slot liefern" in {
      // Mo (0) Slot 1: e1 in a-1.02.
      ConflictRules.occupiedRoomIds(base, event("e4"), 0, 1) shouldBe Set("a-1.02")
    }

    "den eigenen Raum nicht als belegt melden" in {
      ConflictRules.occupiedRoomIds(base, event("e1"), 0, 1) shouldBe Set.empty
    }
  }
