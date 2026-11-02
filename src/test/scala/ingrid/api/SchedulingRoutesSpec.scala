package ingrid.api

import org.http4s.Status
import ingrid.support.ApiSpec

/** `PUT/DELETE /events/{id}/placement`, `POST /placement-check`. */
final class SchedulingRoutesSpec extends ApiSpec:

  private def at(day: Int, slot: Int) = jsonOf("day" -> day.j, "slot" -> slot.j)
  private def at(day: Int, slot: Int, roomId: String) =
    jsonOf("day" -> day.j, "slot" -> slot.j, "roomId" -> roomId.j)

  "PUT /events/{id}/placement" should {
    "ohne roomId den kleinsten passenden freien Raum wählen" in withApi { api =>
      // e4: Übung, 35 TN -> b-0.08 (36 Plätze).
      val res = api.put("/events/e4/placement", at(4, 5))
      res.status shouldBe Status.Ok
      res.json.hcursor.downField("placement").get[String]("roomId").toOption shouldBe Some("b-0.08")
      res.codes shouldBe empty
    }

    "einen bereits zugewiesenen Raum beim Verschieben behalten" in withApi { api =>
      api.put("/events/e4/placement", at(4, 5))
      val res = api.put("/events/e4/placement", at(4, 4))
      res.json.hcursor.downField("placement").get[String]("roomId").toOption shouldBe Some("b-0.08")
      res.json.hcursor.downField("placement").get[Int]("slot").toOption shouldBe Some(4)
    }

    "einen explizit gewählten Raum übernehmen" in withApi { api =>
      val res = api.put("/events/e4/placement", at(4, 5, "c-0.01"))
      res.json.hcursor.downField("placement").get[String]("roomId").toOption shouldBe Some("c-0.01")
    }

    "trotz Konflikt speichern (Soft Constraint)" in withApi { api =>
      val res = api.put("/events/e4/placement", at(0, 1))
      res.status shouldBe Status.Ok
      res.codes should contain("COHORT_OVERLAP")

      // Die Platzierung ist wirklich gespeichert.
      val reloaded = api.get("/events/e4")
      reloaded.json.hcursor.downField("placement").get[Int]("day").toOption shouldBe Some(0)
      reloaded.codes should contain("COHORT_OVERLAP")
    }

    "ROOM_TOO_SMALL melden, wenn der gewählte Raum zu klein ist" in withApi { api =>
      val res = api.put("/events/e4/placement", at(4, 5, "b-1.21"))
      res.codes should contain("ROOM_TOO_SMALL")
      res.json.hcursor.downField("placement").get[String]("roomId").toOption shouldBe Some("b-1.21")
    }

    "ROOM_DOUBLE_BOOKED melden" in withApi { api =>
      val res = api.put("/events/e5/placement", at(0, 1, "a-1.02"))
      res.codes should contain("ROOM_DOUBLE_BOOKED")
    }

    "LECTURER_UNAVAILABLE melden" in withApi { api =>
      // braun ist Mo (0) Slot 4 nicht verfügbar.
      api.put("/events/e7/placement", at(0, 4)).codes should contain("LECTURER_UNAVAILABLE")
    }

    "LECTURER_DOUBLE_BOOKED melden" in withApi { api =>
      // vogt hält e12 am Mi (2) Slot 1.
      api.put("/events/e4/placement", at(2, 1)).codes should contain("LECTURER_DOUBLE_BOOKED")
    }

    "NO_ROOM melden, wenn kein Raum passt" in withApi { api =>
      // Eine Veranstaltung, die in keinen Raum passt.
      val id = api
        .post(
          "/events",
          jsonOf(
            "title"                -> "Massenvorlesung".j,
            "type"                 -> "lecture".j,
            "lecturerId"           -> "becker".j,
            "cohortId"             -> "inf3".j,
            "expectedParticipants" -> 5000.j
          )
        )
        .str("id")
      val res = api.put(s"/events/$id/placement", at(4, 5))
      res.status shouldBe Status.Ok
      res.opt("placement").hcursor.downField("roomId").focus.value.isNull shouldBe true
      res.codes should contain("NO_ROOM")
    }

    "die Belegungszahlen fortschreiben" in withApi { api =>
      def used(roomId: String): Int =
        api
          .get("/rooms?termId=ws2026")
          .items
          .find(_.hcursor.get[String]("id").toOption.contains(roomId))
          .value
          .hcursor
          .get[Int]("usedSlots")
          .toOption
          .value

      used("a-2.15") shouldBe 0
      api.put("/events/e4/placement", at(4, 5, "a-2.15"))
      used("a-2.15") shouldBe 1
    }

    "den Fortschritt des Plans fortschreiben" in withApi { api =>
      api.get("/plans/p1").int("placedCount") shouldBe 5
      api.put("/events/e4/placement", at(4, 5))
      api.get("/plans/p1").int("placedCount") shouldBe 6
    }

    "auch für ein Semester ohne Plan funktionieren" in withApi { api =>
      // inf1 hat noch keinen Plan – es darf trotzdem eingeplant werden.
      api.get("/cohorts/inf1").opt("planId").isNull shouldBe true
      api.put("/events/e14/placement", at(4, 5)).status shouldBe Status.Ok
      api.get("/cohorts/inf1").int("placedCount") shouldBe 5
      api.delete("/events/e14/placement").status shouldBe Status.Ok
    }

    "ungültige Werte mit 400 ablehnen" in withApi { api =>
      api.put("/events/e4/placement", at(0, 6)).status shouldBe Status.BadRequest
      api.put("/events/e4/placement", at(0, -1)).status shouldBe Status.BadRequest
      api.put("/events/e4/placement", at(5, 0)).status shouldBe Status.BadRequest
      api.put("/events/e4/placement", at(-1, 0)).status shouldBe Status.BadRequest
      api.put("/events/e4/placement", at(0, 6)).str("code") shouldBe "VALIDATION_ERROR"
    }

    "den Zustand bei ungültiger Anfrage nicht verändern" in withApi { api =>
      api.put("/events/e4/placement", at(0, 9)).status shouldBe Status.BadRequest
      api.get("/events/e4").opt("placement").isNull shouldBe true
    }

    "unbekannte Veranstaltung oder unbekannten Raum mit 404 ablehnen" in withApi { api =>
      api.put("/events/e99/placement", at(0, 0)).status shouldBe Status.NotFound
      api.put("/events/e4/placement", at(0, 0, "x-9.99")).status shouldBe Status.NotFound
    }

    "unvollständige Anfragen mit 400 ablehnen" in withApi { api =>
      api.put("/events/e4/placement", jsonOf("day" -> 0.j)).status shouldBe Status.BadRequest
    }
  }

  "DELETE /events/{id}/placement" should {
    "die Veranstaltung zurück in die Leiste legen" in withApi { api =>
      val res = api.delete("/events/e1/placement")
      res.status shouldBe Status.Ok
      res.opt("placement").isNull shouldBe true
      res.codes shouldBe empty
      api.get("/events/e1").opt("placement").isNull shouldBe true
    }

    "den Kalender des Raums entlasten" in withApi { api =>
      api.delete("/events/e1/placement")
      api.get("/rooms/a-1.02/schedule?termId=ws2026").opt("entries").asArray.value should have size 4
    }

    "den Fortschritt des Plans zurücksetzen" in withApi { api =>
      api.delete("/events/e1/placement")
      api.get("/plans/p1").int("placedCount") shouldBe 4
      api.get("/cohorts/inf3").int("placedCount") shouldBe 4
    }

    "auf eine nicht eingeplante Veranstaltung anwendbar bleiben" in withApi { api =>
      api.delete("/events/e4/placement").status shouldBe Status.Ok
    }

    "bei unbekannter Veranstaltung 404 liefern" in withApi { api =>
      api.delete("/events/e99/placement").status shouldBe Status.NotFound
    }
  }

  "POST /placement-check" should {
    "genau eine Antwort pro Zelle liefern (5 x 6)" in withApi { api =>
      val res = api.post("/placement-check", jsonOf("eventId" -> "e4".j, "termId" -> "ws2026".j))
      res.status shouldBe Status.Ok
      res.items should have size 30
    }

    "die Zellen in Tages-, dann Slot-Reihenfolge liefern" in withApi { api =>
      val cells = api
        .post("/placement-check", jsonOf("eventId" -> "e4".j, "termId" -> "ws2026".j))
        .items
        .map(j => (j.hcursor.get[Int]("day").toOption.value, j.hcursor.get[Int]("slot").toOption.value))
      cells.head shouldBe (0, 0)
      cells.last shouldBe (4, 5)
      cells shouldBe cells.sorted
    }

    "für freie Zellen einen Raum vorschlagen und keine Konflikte melden" in withApi { api =>
      val cell = api
        .post("/placement-check", jsonOf("eventId" -> "e4".j, "termId" -> "ws2026".j))
        .items
        .find(j =>
          j.hcursor.get[Int]("day").toOption.contains(0) && j.hcursor.get[Int]("slot").toOption.contains(0)
        )
        .value
      cell.hcursor.get[String]("suggestedRoomId").toOption shouldBe Some("b-0.08")
      cell.hcursor.downField("conflicts").focus.value.asArray.value shouldBe empty
    }

    "für belegte Zellen die Konflikte melden" in withApi { api =>
      val cells = api.post("/placement-check", jsonOf("eventId" -> "e4".j, "termId" -> "ws2026".j)).items

      def conflictsAt(day: Int, slot: Int): Vector[String] =
        cells
          .find(j =>
            j.hcursor.get[Int]("day").toOption.contains(day) &&
              j.hcursor.get[Int]("slot").toOption.contains(slot)
          )
          .value
          .hcursor
          .downField("conflicts")
          .focus
          .value
          .asArray
          .value
          .map(_.hcursor.get[String]("code").toOption.value)

      conflictsAt(0, 1) shouldBe Vector("COHORT_OVERLAP")
      conflictsAt(1, 3) shouldBe Vector("LECTURER_DOUBLE_BOOKED")
      conflictsAt(2, 3) shouldBe Vector("LECTURER_DOUBLE_BOOKED", "COHORT_OVERLAP")
      conflictsAt(4, 5) shouldBe empty
    }

    "nie einen belegten Raum vorschlagen" in withApi { api =>
      // b-0.08 ist am Mi (2) Slot 1 durch e12 belegt -> Ausweichraum.
      val cell = api
        .post("/placement-check", jsonOf("eventId" -> "e4".j, "termId" -> "ws2026".j))
        .items
        .find(j =>
          j.hcursor.get[Int]("day").toOption.contains(2) && j.hcursor.get[Int]("slot").toOption.contains(1)
        )
        .value
      cell.hcursor.get[String]("suggestedRoomId").toOption shouldBe Some("a-2.14")
    }

    "unbekannte Veranstaltung oder unbekannten Zeitraum mit 404 ablehnen" in withApi { api =>
      api.post("/placement-check", jsonOf("eventId" -> "e99".j, "termId" -> "ws2026".j)).status shouldBe
        Status.NotFound
      api.post("/placement-check", jsonOf("eventId" -> "e4".j, "termId" -> "xx".j)).status shouldBe
        Status.NotFound
    }

    "unvollständige Anfragen mit 400 ablehnen" in withApi { api =>
      api.post("/placement-check", jsonOf("eventId" -> "e4".j)).status shouldBe Status.BadRequest
    }
  }
