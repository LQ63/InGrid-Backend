package ingrid.scheduling

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import ingrid.commons.*
import ingrid.support.ApiSpec

/** Tests der abgeleiteten Felder. Sollwerte sind die vorberechneten Zahlen der Demodaten. */
final class ProjectionsSpec extends AnyWordSpec with Matchers:

  private val base: Snapshot = ApiSpec.snapshotForUnitTests

  "usedSlots" should {
    "die Belegung je Raum aus den Platzierungen berechnen" in {
      // Sollwerte: usedSlots-Feld der Demodaten.
      val expected = Map(
        "c-0.01" -> 3,
        "a-1.01" -> 2,
        "a-1.02" -> 5,
        "c-1.10" -> 1,
        "c-3.01" -> 2,
        "c-3.02" -> 1,
        "a-2.14" -> 1,
        "a-2.15" -> 0,
        "a-2.16" -> 1,
        "b-0.08" -> 1,
        "b-0.09" -> 1,
        "b-1.20" -> 1,
        "b-1.21" -> 1
      )
      for (roomId, used) <- expected do
        withClue(s"Raum $roomId: ") {
          Projections.usedSlots(base, roomId, Some("ws2026")) shouldBe used
        }
    }

    "ohne Semesterfilter alle Platzierungen zählen" in {
      Projections.usedSlots(base, "a-1.02", None) shouldBe 5
    }

    "Platzierungen eines anderen Zeitraums nicht zählen" in {
      Projections.usedSlots(base, "a-1.02", Some("ss2027")) shouldBe 0
    }
  }

  "eventCount der Dozierenden" should {
    "alle Veranstaltungen zählen, auch nicht eingeplante" in {
      // Sollwerte: eventCount-Feld der Demodaten.
      Projections.lecturerEventCount(base, "becker", Some("ws2026")) shouldBe 3
      Projections.lecturerEventCount(base, "wolf", Some("ws2026")) shouldBe 2
      Projections.lecturerEventCount(base, "neumann", Some("ws2026")) shouldBe 4
      Projections.lecturerEventCount(base, "vogt", Some("ws2026")) shouldBe 5
      Projections.lecturerEventCount(base, "braun", Some("ws2026")) shouldBe 3
    }
  }

  "Zählwerte der Semester" should {
    "placedCount und eventCount aus den Demodaten reproduzieren" in {
      val expected = Map(
        "inf1"  -> (4, 5),
        "inf3"  -> (5, 9),
        "inf5"  -> (3, 4),
        "winf1" -> (3, 3),
        "winf3" -> (2, 2),
        "ds1"   -> (3, 3)
      )
      for (cohortId, (placed, total)) <- expected do
        withClue(s"Semester $cohortId: ") {
          val events = Projections.cohortEvents(base, cohortId)
          events.count(Projections.isPlaced) shouldBe placed
          events should have size total
        }
    }
  }

  "conflictCount" should {
    "nur Veranstaltungen mit mindestens einem Konflikt zählen" in {
      // Sollwerte: conflictCount der Pläne p1/p2/p3.
      Projections.cohortEvents(base, "inf3").count(Projections.hasConflict(base, _)) shouldBe 1
      Projections.cohortEvents(base, "winf1").count(Projections.hasConflict(base, _)) shouldBe 0
      Projections.cohortEvents(base, "ds1").count(Projections.hasConflict(base, _)) shouldBe 0
      Projections.cohortEvents(base, "winf3").count(Projections.hasConflict(base, _)) shouldBe 1
    }
  }

  "Kalenderprojektion" should {
    "nur eingeplante Veranstaltungen enthalten" in {
      val schedule = Projections.schedule(base, Projections.cohortEvents(base, "inf3"))
      schedule.entries should have size 5
      schedule.entries.map(_.eventId) should contain noElementsOf List("e4", "e5", "e7", "e9")
    }

    "nach Tag, Slot und Veranstaltung sortiert sein" in {
      val schedule = Projections.schedule(base, base.events)
      val keys     = schedule.entries.map(e => (e.day, e.slot, e.eventId))
      keys shouldBe keys.sorted
    }

    "Referenzen für die Kalenderkarten auflösen" in {
      val entry = Projections
        .schedule(base, Projections.cohortEvents(base, "inf3"))
        .entries
        .find(_.eventId == "e1")
        .getOrElse(fail("e1 fehlt"))
      entry.lecturer.shortName shouldBe "Becker"
      entry.cohort.program.abbreviation shouldBe "INF"
      entry.cohort.semester shouldBe 3
      entry.roomId shouldBe Some("a-1.02")
      entry.conflicts shouldBe empty
    }

    "Konflikte an den Einträgen mitführen" in {
      val entry = Projections
        .schedule(base, Projections.cohortEvents(base, "inf3"))
        .entries
        .find(_.eventId == "e8")
        .getOrElse(fail("e8 fehlt"))
      entry.conflicts.map(_.code) shouldBe List(ConflictCode.LecturerUnavailable)
    }
  }

  "Veranstaltungsprojektion" should {
    "Platzierung und Konflikte abbilden" in {
      val placed = Projections.event(base, base.requireEvent("e8"))
      placed.placement shouldBe Some(PlacementDto(3, 2, Some("a-1.02")))
      placed.conflicts should have size 1
      placed.lecturer shouldBe LecturerRefDto("wolf", "Prof. Dr. Stefan Wolf", "Wolf")

      val open = Projections.event(base, base.requireEvent("e4"))
      open.placement shouldBe None
      open.conflicts shouldBe empty
    }
  }

  "Raum- und Dozentenauswahl" should {
    "nur Veranstaltungen des jeweiligen Raums liefern" in {
      Projections.roomEvents(base, "a-1.02", Some("ws2026")).map(_.id).sorted shouldBe
        List("e1", "e15", "e20", "e3", "e8").sorted
    }

    "auch nicht eingeplante Veranstaltungen der Dozierenden liefern" in {
      Projections.lecturerEvents(base, "vogt", Some("ws2026")).map(_.id).sorted shouldBe
        List("e2", "e4", "e9", "e12", "e21").sorted
    }
  }
