package ingrid.api

import org.http4s.Status
import ingrid.support.ApiSpec

/** `POST /events`, `GET /events/{id}`, `DELETE /events/{id}`. */
final class EventRoutesSpec extends ApiSpec:

  private val newEvent = jsonOf(
    "title"                -> "Software Engineering".j,
    "type"                 -> "exercise".j,
    "lecturerId"           -> "vogt".j,
    "cohortId"             -> "inf3".j,
    "expectedParticipants" -> 35.j
  )

  "GET /events/{id}" should {
    "das Detailpanel einer eingeplanten Veranstaltung liefern" in withApi { api =>
      val res = api.get("/events/e8")
      res.status shouldBe Status.Ok
      res.str("title") shouldBe "Theoretische Informatik"
      res.str("type") shouldBe "lecture"
      res.str("cohortId") shouldBe "inf3"
      res.int("expectedParticipants") shouldBe 140
      res.json.hcursor.downField("lecturer").get[String]("shortName").toOption shouldBe Some("Wolf")
      res.json.hcursor.downField("placement").get[Int]("day").toOption shouldBe Some(3)
      res.json.hcursor.downField("placement").get[Int]("slot").toOption shouldBe Some(2)
      res.json.hcursor.downField("placement").get[String]("roomId").toOption shouldBe Some("a-1.02")
      res.codes shouldBe Vector("LECTURER_UNAVAILABLE")
    }

    "placement null liefern, wenn nicht eingeplant" in withApi { api =>
      val res = api.get("/events/e4")
      res.opt("placement").isNull shouldBe true
      res.codes shouldBe empty
    }

    "bei unbekannter Veranstaltung 404 liefern" in withApi { api =>
      val res = api.get("/events/e99")
      res.status shouldBe Status.NotFound
      res.str("code") shouldBe "NOT_FOUND"
      res.str("message") should include("e99")
    }
  }

  "POST /events" should {
    "eine Veranstaltung ohne Platzierung anlegen" in withApi { api =>
      val res = api.post("/events", newEvent)
      res.status shouldBe Status.Created
      res.str("title") shouldBe "Software Engineering"
      res.str("type") shouldBe "exercise"
      res.opt("placement").isNull shouldBe true
      res.codes shouldBe empty
      res.str("id") should fullyMatch regex """e\d+"""
      res.json.hcursor.downField("lecturer").get[String]("name").toOption shouldBe
        Some("Dr. Lena Vogt")
    }

    "die neue Veranstaltung dem Semester zuordnen" in withApi { api =>
      val id = api.post("/events", newEvent).str("id")
      api.get("/cohorts/inf3").int("eventCount") shouldBe 10
      api.get("/cohorts/inf3/events").ids should contain(id)
      api.get("/plans/p1").int("eventCount") shouldBe 10
      api.get(s"/events/$id").status shouldBe Status.Ok
    }

    "eine eindeutige ID vergeben" in withApi { api =>
      val first  = api.post("/events", newEvent).str("id")
      val second = api.post("/events", newEvent).str("id")
      first should not be second
    }

    "Leerzeichen im Titel entfernen" in withApi { api =>
      api.post("/events", newEvent.deepMerge(jsonOf("title" -> "  Mathematik  ".j))).str("title") shouldBe
        "Mathematik"
    }

    "leeren Titel mit 400 ablehnen" in withApi { api =>
      api.post("/events", newEvent.deepMerge(jsonOf("title" -> "".j))).status shouldBe Status.BadRequest
      api.post("/events", newEvent.deepMerge(jsonOf("title" -> "   ".j))).status shouldBe
        Status.BadRequest
    }

    "negative Teilnehmerzahl mit 400 ablehnen" in withApi { api =>
      api
        .post("/events", newEvent.deepMerge(jsonOf("expectedParticipants" -> (-1).j)))
        .status shouldBe Status.BadRequest
    }

    "unbekannten Veranstaltungstyp mit 400 ablehnen" in withApi { api =>
      api.post("/events", newEvent.deepMerge(jsonOf("type" -> "unsinn".j))).status shouldBe
        Status.BadRequest
    }

    "unbekannte Person oder unbekanntes Semester mit 404 ablehnen" in withApi { api =>
      api.post("/events", newEvent.deepMerge(jsonOf("lecturerId" -> "niemand".j))).status shouldBe
        Status.NotFound
      api.post("/events", newEvent.deepMerge(jsonOf("cohortId" -> "gibtsnicht".j))).status shouldBe
        Status.NotFound
    }

    "unvollständige Anfragen mit 400 ablehnen" in withApi { api =>
      api.post("/events", jsonOf("title" -> "Nur ein Titel".j)).status shouldBe Status.BadRequest
    }
  }

  "DELETE /events/{id}" should {
    "die Veranstaltung ohne Inhalt löschen" in withApi { api =>
      val res = api.delete("/events/e4")
      res.status shouldBe Status.NoContent
      api.get("/events/e4").status shouldBe Status.NotFound
      api.get("/cohorts/inf3").int("eventCount") shouldBe 8
    }

    "eine eingeplante Veranstaltung aus dem Kalender entfernen" in withApi { api =>
      api.delete("/events/e1").status shouldBe Status.NoContent
      api.get("/rooms/a-1.02/schedule?termId=ws2026").opt("entries").asArray.value should have size 4
      api.get("/rooms?termId=ws2026").items
        .find(_.hcursor.get[String]("id").toOption.contains("a-1.02"))
        .value
        .hcursor
        .get[Int]("usedSlots")
        .toOption shouldBe Some(4)
    }

    "Konflikte anderer Veranstaltungen auflösen" in withApi { api =>
      // e22 ist der einzige Konflikt in winf3; nach dem Löschen ist die Kohorte konfliktfrei.
      api.get("/cohorts/winf3/events?status=conflict").ids shouldBe Vector("e22")
      api.delete("/events/e22").status shouldBe Status.NoContent
      api.get("/cohorts/winf3/events?status=conflict").items shouldBe empty
    }

    "bei unbekannter Veranstaltung 404 liefern" in withApi { api =>
      api.delete("/events/e99").status shouldBe Status.NotFound
    }

    "nicht zweimal löschen" in withApi { api =>
      api.delete("/events/e4").status shouldBe Status.NoContent
      api.delete("/events/e4").status shouldBe Status.NotFound
    }
  }
