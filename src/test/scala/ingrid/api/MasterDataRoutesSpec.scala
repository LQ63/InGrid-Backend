package ingrid.api

import org.http4s.Status
import ingrid.support.ApiSpec

/** `GET /terms`, `/timeslots`, `/programs`, `/stats`. */
final class MasterDataRoutesSpec extends ApiSpec:

  "GET /terms" should {
    "die Semesterzeiträume liefern" in withApi { api =>
      val res = api.get("/terms")
      res.status shouldBe Status.Ok
      res.ids shouldBe Vector("ws2026", "ss2027")
      res.items.head.hcursor.get[String]("name").toOption shouldBe Some("WiSe 2026/27")
      res.items.head.hcursor.get[String]("startDate").toOption shouldBe Some("2026-10-01")
    }
  }

  "GET /timeslots" should {
    "das Zeitraster nach Slot sortiert liefern" in withApi { api =>
      val res = api.get("/timeslots")
      res.status shouldBe Status.Ok
      res.items should have size 6
      res.items.map(_.hcursor.get[Int]("slot").toOption.get) shouldBe Vector(0, 1, 2, 3, 4, 5)
      res.items.head.hcursor.get[String]("start").toOption shouldBe Some("08:00")
      res.items.last.hcursor.get[String]("end").toOption shouldBe Some("19:00")
    }
  }

  "GET /programs" should {
    "die Studiengänge liefern" in withApi { api =>
      val res = api.get("/programs")
      res.status shouldBe Status.Ok
      res.ids should contain allOf ("inf", "winf", "ds")
      res.items.head.hcursor.get[String]("abbreviation").toOption shouldBe Some("INF")
    }
  }

  "GET /stats" should {
    "die Kennzahlen der Startseite liefern" in withApi { api =>
      val res = api.get("/stats?termId=ws2026")
      res.status shouldBe Status.Ok
      res.int("rooms") shouldBe 13
      res.int("lecturers") shouldBe 8
      res.int("cohorts") shouldBe 6
      res.int("plansInProgress") shouldBe 3
    }

    "ohne termId alles zählen" in withApi { api =>
      api.get("/stats").int("cohorts") shouldBe 6
    }

    "nach Semesterzeitraum filtern" in withApi { api =>
      val res = api.get("/stats?termId=ss2027")
      res.int("cohorts") shouldBe 0
      res.int("plansInProgress") shouldBe 0
      res.int("rooms") shouldBe 13 // Stammdaten sind zeitraumunabhängig
    }

    "die Zahlen nach einer Änderung fortschreiben" in withApi { api =>
      api.post(
        "/cohorts",
        jsonOf("programId" -> "ds".j, "semester" -> 3.j, "termId" -> "ws2026".j, "students" -> 40.j)
      ).status shouldBe Status.Created
      api.get("/stats?termId=ws2026").int("cohorts") shouldBe 7
    }
  }
