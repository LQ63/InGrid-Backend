package ingrid.commons

import cats.effect.unsafe.implicits.global
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import ingrid.support.ApiSpec

/** Tests der Basistypen, des Demodaten-Ladens und des Stores. */
final class CommonsSpec extends AnyWordSpec with Matchers:

  private val base: Snapshot = ApiSpec.snapshotForUnitTests

  "Seed" should {
    "den vollständigen Demodatensatz laden" in {
      base.terms should have size 2
      base.timeslots should have size 6
      base.programs should have size 3
      base.rooms should have size 13
      base.lecturers should have size 8
      base.cohorts should have size 6
      base.plans should have size 3
      base.events should have size 26
    }

    "verschachtelte Referenzen auf IDs reduzieren" in {
      base.requireEvent("e1").lecturerId shouldBe "becker"
      base.requireEvent("e1").cohortId shouldBe "inf3"
      base.requireCohort("inf3").programId shouldBe "inf"
      base.requirePlan("p1").cohortId shouldBe "inf3"
      base.requirePlan("p1").termId shouldBe "ws2026"
    }

    "Platzierungen inklusive fehlender Werte lesen" in {
      base.requireEvent("e1").placement shouldBe Some(Placement(0, 1, Some("a-1.02")))
      base.requireEvent("e4").placement shouldBe None
    }

    "Enums aus ihren Wire-Codes auflösen" in {
      base.requireRoom("b-1.20").kind shouldBe RoomType.Lab
      base.requireEvent("e2").kind shouldBe EventType.Exercise
      base.requirePlan("p3").status shouldBe PlanStatus.PendingApproval
    }
  }

  "Snapshot" should {
    "Nachschlagen über Indizes erlauben" in {
      base.roomById("a-1.02").name shouldBe "A 1.02"
      base.lecturerById("becker").shortName shouldBe "Becker"
      base.planByCohortId("inf3").id shouldBe "p1"
      base.planByCohortId.get("inf1") shouldBe None
      base.eventsByCohortId("inf3") should have size 9
    }

    "den Semesterzeitraum einer Veranstaltung auflösen" in {
      base.termOf(base.requireEvent("e1")) shouldBe Some("ws2026")
      base.timeslot(2) shouldBe Some(Timeslot(2, "11:30", "13:00"))
      base.timeslot(9) shouldBe None
    }

    "bei unbekannten IDs ApiError.notFound werfen" in {
      the[ApiError] thrownBy base.requireRoom("x") should have(Symbol("status")(404))
      the[ApiError] thrownBy base.requireLecturer("x") should have(Symbol("status")(404))
      the[ApiError] thrownBy base.requireCohort("x") should have(Symbol("status")(404))
      the[ApiError] thrownBy base.requirePlan("x") should have(Symbol("status")(404))
      the[ApiError] thrownBy base.requireEvent("x") should have(Symbol("status")(404))
      the[ApiError] thrownBy base.requireProgram("x") should have(Symbol("status")(404))
      the[ApiError] thrownBy base.requireTerm("x") should have(Symbol("status")(404))
    }

    "eine Veranstaltung ersetzen, ohne die anderen zu verändern" in {
      val changed = base.replaceEvent(base.requireEvent("e1").copy(title = "Neu"))
      changed.requireEvent("e1").title shouldBe "Neu"
      changed.events should have size base.events.size
      changed.requireEvent("e2").title shouldBe base.requireEvent("e2").title
    }
  }

  "Store" should {
    "Änderungen übernehmen" in {
      val store = Store.seeded.unsafeRunSync()
      store.mutate(s => (s.withEvents(Nil), s.events.size)).unsafeRunSync() shouldBe 26
      store.read.unsafeRunSync().events shouldBe empty
    }

    "den Zustand bei einem ApiError unverändert lassen" in {
      val store = Store.seeded.unsafeRunSync()
      val failing = store.mutate[Int] { s =>
        val _ = s.withEvents(Nil)
        throw ApiError.conflict("Abbruch")
      }
      the[ApiError] thrownBy failing.unsafeRunSync() should have(Symbol("status")(409))
      store.read.unsafeRunSync().events should have size 26
    }

    "Fehler aus query als IO-Fehler weitergeben" in {
      val store = Store.seeded.unsafeRunSync()
      the[ApiError] thrownBy store.query(_.requireEvent("e99")).unsafeRunSync() should
        have(Symbol("status")(404))
    }

    "nebenläufige Änderungen nicht verlieren" in {
      val store = Store.seeded.unsafeRunSync()
      val adds = (1 to 50).toList.map { i =>
        store.mutate { s =>
          val e = Event(s"n$i", s"Neu $i", EventType.Lecture, "becker", "inf3", 10, None)
          (s.withEvents(s.events :+ e), ())
        }
      }
      import cats.syntax.parallel.*
      adds.parSequence_.unsafeRunSync()
      store.read.unsafeRunSync().events should have size 76
    }
  }

  "Search" should {
    "ohne Suchbegriff alles durchlassen" in {
      Search.matches(None, "egal") shouldBe true
      Search.matches(Some(""), "egal") shouldBe true
      Search.matches(Some("   "), "egal") shouldBe true
    }

    "Gross-/Kleinschreibung ignorieren und Teiltreffer erlauben" in {
      Search.matches(Some("BECK"), "Prof. Dr. Anna Becker") shouldBe true
      Search.matches(Some("anna"), "Prof. Dr. Anna Becker") shouldBe true
      Search.matches(Some("xyz"), "Prof. Dr. Anna Becker") shouldBe false
    }

    "über mehrere Felder suchen" in {
      Search.matches(Some("informatik"), "A 1.02", "Informatik") shouldBe true
      Search.matches(Some("informatik")) shouldBe false
    }
  }

  "Grid" should {
    "das Zeitraster der Hochschule abbilden" in {
      Grid.days shouldBe 5
      Grid.slots shouldBe 6
      Grid.totalSlots shouldBe 30
      Grid.all should have size 30
      Grid.all.head shouldBe DaySlot(0, 0)
      Grid.all.last shouldBe DaySlot(4, 5)
      Grid.dayNames should have size 5
    }

    "Grenzen prüfen" in {
      Grid.validDay(0) shouldBe true
      Grid.validDay(4) shouldBe true
      Grid.validDay(-1) shouldBe false
      Grid.validDay(5) shouldBe false
      Grid.validSlot(0) shouldBe true
      Grid.validSlot(5) shouldBe true
      Grid.validSlot(-1) shouldBe false
      Grid.validSlot(6) shouldBe false
    }

    "die Zellen in Tages-, dann Slot-Reihenfolge liefern" in {
      Grid.all.take(7).toList shouldBe List(
        DaySlot(0, 0),
        DaySlot(0, 1),
        DaySlot(0, 2),
        DaySlot(0, 3),
        DaySlot(0, 4),
        DaySlot(0, 5),
        DaySlot(1, 0)
      )
    }
  }

  "Codes" should {
    "Wire-Codes auflösen und Unbekanntes ablehnen" in {
      Codes.roomType("pc_pool") shouldBe Some(RoomType.PcPool)
      Codes.roomType("unsinn") shouldBe None
      Codes.eventType("lab") shouldBe Some(EventType.Lab)
      Codes.planStatus("pending_approval") shouldBe Some(PlanStatus.PendingApproval)
      Codes.eventStatusFilter("conflict") shouldBe Some(EventStatusFilter.Conflict)
    }

    "für jeden Enum-Case einen eindeutigen Code liefern" in {
      RoomType.values.map(_.code).distinct should have size RoomType.values.length
      EventType.values.map(_.code).distinct should have size EventType.values.length
      ConflictCode.values.map(_.code).distinct should have size ConflictCode.values.length
      EventType.values.map(_.short).distinct should have size EventType.values.length
    }
  }

  "Clock" should {
    "einen ISO-Zeitstempel mit Offset liefern" in {
      Clock.nowIso should fullyMatch regex """\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}([+-]\d{2}:\d{2}|Z)"""
    }
  }

  "ApiError" should {
    "die Fehlerkategorien der API abbilden" in {
      ApiError.notFound("x") shouldBe ApiError(404, "NOT_FOUND", "x")
      ApiError.badRequest("x") shouldBe ApiError(400, "VALIDATION_ERROR", "x")
      ApiError.conflict("x") shouldBe ApiError(409, "CONFLICT", "x")
      ApiError.notFound("x").getMessage shouldBe "x"
    }
  }
