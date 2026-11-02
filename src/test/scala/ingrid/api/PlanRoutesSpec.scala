package ingrid.api

import org.http4s.Status
import ingrid.support.ApiSpec

/** `GET/POST /plans`, `/plans/{id}`, `/plans/{id}/events`. */
final class PlanRoutesSpec extends ApiSpec:

  private val newCohort =
    jsonOf("programId" -> "ds".j, "semester" -> 3.j, "termId" -> "ws2026".j, "students" -> 40.j)

  "GET /plans" should {
    "die Pläne mit Fortschritt und Konfliktzahl liefern" in withApi { api =>
      val res = api.get("/plans?termId=ws2026")
      res.status shouldBe Status.Ok
      res.items should have size 3

      val p1 = res.items.find(_.hcursor.get[String]("id").toOption.contains("p1")).value
      p1.hcursor.get[Int]("placedCount").toOption shouldBe Some(5)
      p1.hcursor.get[Int]("eventCount").toOption shouldBe Some(9)
      p1.hcursor.get[Int]("conflictCount").toOption shouldBe Some(1)
      p1.hcursor.get[String]("status").toOption shouldBe Some("in_progress")
      p1.hcursor.downField("cohort").get[String]("id").toOption shouldBe Some("inf3")
      p1.hcursor.downField("cohort").get[Int]("semester").toOption shouldBe Some(3)
      p1.hcursor.downField("term").get[String]("name").toOption shouldBe Some("WiSe 2026/27")
    }

    "nach dem letzten Änderungszeitpunkt absteigend sortieren" in withApi { api =>
      api.get("/plans").ids shouldBe Vector("p1", "p2", "p3")
    }

    "nach Status filtern" in withApi { api =>
      api.get("/plans?status=in_progress").ids shouldBe Vector("p1", "p2")
      api.get("/plans?status=pending_approval").ids shouldBe Vector("p3")
    }

    "nach Semesterzeitraum filtern" in withApi { api =>
      api.get("/plans?termId=ss2027").items shouldBe empty
    }

    "ungültigen Status mit 400 ablehnen" in withApi { api =>
      val res = api.get("/plans?status=unsinn")
      res.status shouldBe Status.BadRequest
      res.str("code") shouldBe "VALIDATION_ERROR"
    }
  }

  "GET /plans/{id}" should {
    "die Kopfzeile des Planers liefern" in withApi { api =>
      val res = api.get("/plans/p1")
      res.status shouldBe Status.Ok
      res.int("conflictCount") shouldBe 1
      res.int("placedCount") shouldBe 5
    }

    "bei unbekanntem Plan 404 liefern" in withApi { api =>
      val res = api.get("/plans/p99")
      res.status shouldBe Status.NotFound
      res.str("code") shouldBe "NOT_FOUND"
    }
  }

  "GET /plans/{id}/events" should {
    "alle Veranstaltungen liefern, auch nicht eingeplante" in withApi { api =>
      val res = api.get("/plans/p1/events")
      res.status shouldBe Status.Ok
      res.items should have size 9
      res.items.count(_.hcursor.downField("placement").focus.exists(_.isNull)) shouldBe 4
    }

    "bei unbekanntem Plan 404 liefern" in withApi { api =>
      api.get("/plans/p99/events").status shouldBe Status.NotFound
    }
  }

  "POST /plans" should {
    "einen Plan für ein Semester anlegen" in withApi { api =>
      api.post("/cohorts", newCohort).status shouldBe Status.Created

      val res = api.post(
        "/plans",
        jsonOf("termId" -> "ws2026".j, "cohortId" -> "ds3".j, "copyFromCatalog" -> true.j)
      )
      res.status shouldBe Status.Created
      val planId = res.str("id")
      planId should fullyMatch regex """p\d+"""

      val plan = api.get(s"/plans/$planId")
      plan.status shouldBe Status.Ok
      plan.str("status") shouldBe "in_progress"
      plan.json.hcursor.downField("cohort").get[String]("id").toOption shouldBe Some("ds3")
      plan.json.hcursor.downField("term").get[String]("name").toOption shouldBe Some("WiSe 2026/27")
    }

    "das Semester anschliessend auf den Plan verweisen lassen" in withApi { api =>
      api.post("/cohorts", newCohort)
      val planId = api.post("/plans", jsonOf("termId" -> "ws2026".j, "cohortId" -> "ds3".j)).str("id")
      api.get("/cohorts/ds3").str("planId") shouldBe planId
    }

    "ein Semester mit vorhandenen Veranstaltungen nicht duplizieren" in withApi { api =>
      // inf1 hat 5 Veranstaltungen, aber noch keinen Plan.
      val planId = api.post("/plans", jsonOf("termId" -> "ws2026".j, "cohortId" -> "inf1".j)).str("id")
      api.get(s"/plans/$planId/events").items should have size 5
      api.get("/cohorts/inf1").int("eventCount") shouldBe 5
    }

    "copyFromCatalog standardmässig annehmen" in withApi { api =>
      api.post("/plans", jsonOf("termId" -> "ws2026".j, "cohortId" -> "inf1".j)).status shouldBe
        Status.Created
    }

    "ein zweites Mal mit 409 ablehnen" in withApi { api =>
      val res = api.post("/plans", jsonOf("termId" -> "ws2026".j, "cohortId" -> "inf3".j))
      res.status shouldBe Status.Conflict
      res.str("message") should include("p1")
    }

    "einen Zeitraum ablehnen, der nicht zum Semester passt" in withApi { api =>
      val res = api.post("/plans", jsonOf("termId" -> "ss2027".j, "cohortId" -> "inf1".j))
      res.status shouldBe Status.BadRequest
      res.str("code") shouldBe "VALIDATION_ERROR"
    }

    "unbekanntes Semester mit 404 ablehnen" in withApi { api =>
      api.post("/plans", jsonOf("termId" -> "ws2026".j, "cohortId" -> "gibtsnicht".j)).status shouldBe
        Status.NotFound
    }

    "unbekannten Semesterzeitraum mit 404 ablehnen" in withApi { api =>
      val res = api.post("/plans", jsonOf("termId" -> "wise1999".j, "cohortId" -> "inf1".j))
      res.status shouldBe Status.NotFound
      res.str("message") should include("wise1999")
    }

    "unvollständige Anfragen mit 400 ablehnen" in withApi { api =>
      api.post("/plans", jsonOf("termId" -> "ws2026".j)).status shouldBe Status.BadRequest
    }
  }

  "Vorjahres-Rollover" should {
    "Veranstaltungen aus demselben Fachsemester eines anderen Zeitraums übernehmen" in withApi { api =>
      // Gleicher Studiengang, gleiches Fachsemester, anderer Zeitraum -> inf3 dient als Schablone.
      val res = api.post(
        "/cohorts",
        jsonOf("programId" -> "inf".j, "semester" -> 3.j, "termId" -> "ss2027".j, "students" -> 130.j)
      )
      res.status shouldBe Status.Created
      val cohortId = res.str("id")

      val planId = api
        .post(
          "/plans",
          jsonOf("termId" -> "ss2027".j, "cohortId" -> cohortId.j, "copyFromCatalog" -> true.j)
        )
        .str("id")

      val events = api.get(s"/plans/$planId/events")
      events.items should have size 9
      events.items.map(_.hcursor.get[String]("cohortId").toOption.value).distinct shouldBe Vector(cohortId)
      events.ids should contain noElementsOf Vector("e1", "e2", "e3")
    }

    "mit copyFromCatalog=false nichts übernehmen" in withApi { api =>
      val cohortId = api
        .post(
          "/cohorts",
          jsonOf("programId" -> "inf".j, "semester" -> 3.j, "termId" -> "ss2027".j, "students" -> 130.j)
        )
        .str("id")
      val planId = api
        .post(
          "/plans",
          jsonOf("termId" -> "ss2027".j, "cohortId" -> cohortId.j, "copyFromCatalog" -> false.j)
        )
        .str("id")
      api.get(s"/plans/$planId/events").items shouldBe empty
    }

    "nichts übernehmen, wenn es keine Schablone gibt" in withApi { api =>
      api.post("/cohorts", newCohort)
      val planId = api.post("/plans", jsonOf("termId" -> "ws2026".j, "cohortId" -> "ds3".j)).str("id")
      api.get(s"/plans/$planId/events") .items shouldBe empty
    }
  }
