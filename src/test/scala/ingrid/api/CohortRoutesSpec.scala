package ingrid.api

import org.http4s.Status
import ingrid.support.ApiSpec

/** `GET/POST /cohorts`, `/cohorts/{id}`, `/cohorts/{id}/events`, `/cohorts/{id}/schedule`. */
final class CohortRoutesSpec extends ApiSpec:

  private val dsCohort =
    jsonOf("programId" -> "ds".j, "semester" -> 3.j, "termId" -> "ws2026".j, "students" -> 40.j)

  "GET /cohorts" should {
    "alle Semester mit Fortschritt und Plan-Verweis liefern" in withApi { api =>
      val res = api.get("/cohorts?termId=ws2026")
      res.status shouldBe Status.Ok
      res.items should have size 6

      val inf3 = res.items.find(_.hcursor.get[String]("id").toOption.contains("inf3")).value
      inf3.hcursor.get[Int]("semester").toOption shouldBe Some(3)
      inf3.hcursor.get[Int]("students").toOption shouldBe Some(140)
      inf3.hcursor.get[Int]("placedCount").toOption shouldBe Some(5)
      inf3.hcursor.get[Int]("eventCount").toOption shouldBe Some(9)
      inf3.hcursor.get[String]("planId").toOption shouldBe Some("p1")
      inf3.hcursor.downField("program").get[String]("abbreviation").toOption shouldBe Some("INF")
    }

    "planId null liefern, wenn noch kein Plan existiert" in withApi { api =>
      api
        .get("/cohorts")
        .items
        .find(_.hcursor.get[String]("id").toOption.contains("inf1"))
        .value
        .hcursor
        .downField("planId")
        .focus
        .value
        .isNull shouldBe true
    }

    "nach Studiengang und Fachsemester filtern (Prüfung im Dialog)" in withApi { api =>
      api.get("/cohorts?programId=inf&semester=3&termId=ws2026").ids shouldBe Vector("inf3")
      api.get("/cohorts?programId=ds&semester=3&termId=ws2026").items shouldBe empty
      api.get("/cohorts?programId=inf").items should have size 3
    }

    "nicht-numerisches Fachsemester mit 400 ablehnen" in withApi { api =>
      api.get("/cohorts?semester=drei").status shouldBe Status.BadRequest
    }

    "nach Semesterzeitraum filtern" in withApi { api =>
      api.get("/cohorts?termId=ss2027").items shouldBe empty
    }

    "per Freitext suchen" in withApi { api =>
      api.get("/cohorts?q=INF").items.size should be >= 3
      api.get("/cohorts?q=Wirtschaftsinformatik").items should have size 2
      api.get("/cohorts?q=gibtsnicht").items shouldBe empty
    }
  }

  "POST /cohorts" should {
    "ein neues Semester anlegen" in withApi { api =>
      val res = api.post("/cohorts", dsCohort)
      res.status shouldBe Status.Created
      res.str("id") shouldBe "ds3"
      res.int("semester") shouldBe 3
      res.int("students") shouldBe 40
      res.int("eventCount") shouldBe 0
      res.int("placedCount") shouldBe 0
      res.opt("planId").isNull shouldBe true
      res.json.hcursor.downField("program").get[String]("abbreviation").toOption shouldBe Some("DS")
    }

    "das neue Semester anschliessend ausliefern" in withApi { api =>
      api.post("/cohorts", dsCohort)
      api.get("/cohorts?termId=ws2026").items should have size 7
      api.get("/cohorts/ds3").status shouldBe Status.Ok
    }

    "ein bereits vorhandenes Semester mit 409 ablehnen" in withApi { api =>
      val res = api.post(
        "/cohorts",
        jsonOf("programId" -> "inf".j, "semester" -> 3.j, "termId" -> "ws2026".j, "students" -> 140.j)
      )
      res.status shouldBe Status.Conflict
      res.str("code") shouldBe "CONFLICT"
      res.str("message") should include("inf3")
    }

    "dasselbe Semester nicht zweimal anlegen" in withApi { api =>
      api.post("/cohorts", dsCohort).status shouldBe Status.Created
      api.post("/cohorts", dsCohort).status shouldBe Status.Conflict
    }

    "dasselbe Fachsemester in einem anderen Zeitraum zulassen" in withApi { api =>
      api.post("/cohorts", dsCohort).status shouldBe Status.Created
      api.post(
        "/cohorts",
        jsonOf("programId" -> "ds".j, "semester" -> 3.j, "termId" -> "ss2027".j, "students" -> 40.j)
      ).status shouldBe Status.Created
    }

    "unbekannten Studiengang mit 404 ablehnen" in withApi { api =>
      api.post(
        "/cohorts",
        jsonOf("programId" -> "xx".j, "semester" -> 1.j, "termId" -> "ws2026".j, "students" -> 10.j)
      ).status shouldBe Status.NotFound
    }

    "unbekannten Semesterzeitraum mit 404 ablehnen" in withApi { api =>
      api.post(
        "/cohorts",
        jsonOf("programId" -> "ds".j, "semester" -> 1.j, "termId" -> "xx".j, "students" -> 10.j)
      ).status shouldBe Status.NotFound
    }

    "unsinnige Werte mit 400 ablehnen" in withApi { api =>
      api.post(
        "/cohorts",
        jsonOf("programId" -> "ds".j, "semester" -> 0.j, "termId" -> "ws2026".j, "students" -> 10.j)
      ).status shouldBe Status.BadRequest
      api.post(
        "/cohorts",
        jsonOf("programId" -> "ds".j, "semester" -> 2.j, "termId" -> "ws2026".j, "students" -> (-1).j)
      ).status shouldBe Status.BadRequest
    }

    "unvollständige Anfragen mit 400 ablehnen" in withApi { api =>
      api.post("/cohorts", jsonOf("programId" -> "ds".j)).status shouldBe Status.BadRequest
    }
  }

  "GET /cohorts/{id}" should {
    "die Kopfzeile des Semester-Details liefern" in withApi { api =>
      val res = api.get("/cohorts/inf3")
      res.status shouldBe Status.Ok
      res.str("id") shouldBe "inf3"
      res.str("planId") shouldBe "p1"
      res.str("termId") shouldBe "ws2026"
    }

    "bei unbekanntem Semester 404 liefern" in withApi { api =>
      api.get("/cohorts/gibtsnicht").status shouldBe Status.NotFound
    }
  }

  "GET /cohorts/{id}/events" should {
    "alle Veranstaltungen des Semesters liefern" in withApi { api =>
      val res = api.get("/cohorts/inf3/events")
      res.status shouldBe Status.Ok
      res.items should have size 9
    }

    "nach Status filtern" in withApi { api =>
      api.get("/cohorts/inf3/events?status=placed").items should have size 5
      api.get("/cohorts/inf3/events?status=open").ids.sorted shouldBe
        Vector("e4", "e5", "e7", "e9").sorted
      api.get("/cohorts/inf3/events?status=conflict").ids shouldBe Vector("e8")
    }

    "nach Typ, Person und Tag filtern" in withApi { api =>
      api.get("/cohorts/inf3/events?type=lecture").items should have size 5
      api.get("/cohorts/inf3/events?type=lab").ids shouldBe Vector("e7")
      api.get("/cohorts/inf3/events?lecturerId=vogt").ids.sorted shouldBe
        Vector("e2", "e4", "e9").sorted
      api.get("/cohorts/inf3/events?day=0").ids shouldBe Vector("e1")
      api.get("/cohorts/inf3/events?day=4").items shouldBe empty
    }

    "per Freitext über Titel und Person suchen" in withApi { api =>
      api.get("/cohorts/inf3/events?q=Algorithmen").items should have size 2
      api.get("/cohorts/inf3/events?q=becker").ids shouldBe Vector("e1")
    }

    "Filter kombinieren" in withApi { api =>
      api.get("/cohorts/inf3/events?type=exercise&status=open").ids.sorted shouldBe
        Vector("e4", "e9").sorted
    }

    "ungültige Filterwerte mit 400 ablehnen" in withApi { api =>
      for query <- List("status=unsinn", "type=unsinn", "day=montag") do
        withClue(s"GET /cohorts/inf3/events?$query: ") {
          val res = api.get(s"/cohorts/inf3/events?$query")
          res.status shouldBe Status.BadRequest
          res.str("code") shouldBe "VALIDATION_ERROR"
        }
    }

    "bei unbekanntem Semester 404 liefern" in withApi { api =>
      api.get("/cohorts/gibtsnicht/events").status shouldBe Status.NotFound
    }
  }

  "GET /cohorts/{id}/schedule" should {
    "nur eingeplante Veranstaltungen liefern" in withApi { api =>
      val res = api.get("/cohorts/inf3/schedule?termId=ws2026")
      res.status shouldBe Status.Ok
      val entries = res.opt("entries").asArray.value
      entries should have size 5
      entries.map(_.hcursor.get[String]("eventId").toOption.value).sorted shouldBe
        Vector("e1", "e2", "e3", "e6", "e8").sorted
    }

    "bei unbekanntem Semester 404 liefern" in withApi { api =>
      api.get("/cohorts/gibtsnicht/schedule").status shouldBe Status.NotFound
    }
  }
