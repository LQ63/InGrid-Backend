package ingrid.api

import org.http4s.Status
import ingrid.support.ApiSpec

/** `GET /lecturers`, `/lecturers/{id}/schedule`. */
final class LecturerRoutesSpec extends ApiSpec:

  "GET /lecturers" should {
    "alle Dozierenden mit Veranstaltungszahl liefern" in withApi { api =>
      val res = api.get("/lecturers?termId=ws2026")
      res.status shouldBe Status.Ok
      res.items should have size 8

      val becker = res.items.find(_.hcursor.get[String]("id").toOption.contains("becker")).value
      becker.hcursor.get[String]("name").toOption shouldBe Some("Prof. Dr. Anna Becker")
      becker.hcursor.get[String]("shortName").toOption shouldBe Some("Becker")
      becker.hcursor.get[String]("department").toOption shouldBe Some("Informatik")
      becker.hcursor.get[Int]("eventCount").toOption shouldBe Some(3)
    }

    "die Nicht-verfügbar-Zeiten mitliefern" in withApi { api =>
      val becker = api
        .get("/lecturers")
        .items
        .find(_.hcursor.get[String]("id").toOption.contains("becker"))
        .value
      val unavailable = becker.hcursor.downField("unavailable").focus.value.asArray.value
      unavailable should have size 3
      unavailable.map(_.hcursor.get[Int]("day").toOption.value).distinct shouldBe Vector(4)
      unavailable.map(_.hcursor.get[Int]("slot").toOption.value) shouldBe Vector(3, 4, 5)
    }

    "eine leere Liste liefern, wenn keine Einschränkungen hinterlegt sind" in withApi { api =>
      api
        .get("/lecturers")
        .items
        .find(_.hcursor.get[String]("id").toOption.contains("vogt"))
        .value
        .hcursor
        .downField("unavailable")
        .focus
        .value
        .asArray
        .value shouldBe empty
    }

    "per Freitext über Name und Fachbereich suchen" in withApi { api =>
      api.get("/lecturers?q=becker").ids shouldBe Vector("becker")
      api.get("/lecturers?q=BECKER").ids shouldBe Vector("becker")
      api.get("/lecturers?q=anna").ids shouldBe Vector("becker")
      api.get("/lecturers?q=Informatik").items.size should be > 1
      api.get("/lecturers?q=gibtsnicht").items shouldBe empty
    }

    "eventCount auf den Semesterzeitraum beziehen" in withApi { api =>
      api
        .get("/lecturers?termId=ss2027")
        .items
        .map(_.hcursor.get[Int]("eventCount").toOption.value)
        .distinct shouldBe Vector(0)
    }
  }

  "GET /lecturers/{id}/schedule" should {
    "Wochenplan und Nicht-verfügbar-Zeiten liefern" in withApi { api =>
      val res = api.get("/lecturers/becker/schedule?termId=ws2026")
      res.status shouldBe Status.Ok

      val entries = res.opt("entries").asArray.value
      entries should have size 2 // e1 und e10 sind eingeplant, e18 nicht
      entries.map(_.hcursor.get[String]("eventId").toOption.value).sorted shouldBe Vector("e1", "e10")

      res.opt("unavailable").asArray.value should have size 3
    }

    "nicht eingeplante Veranstaltungen nicht in den Kalender legen" in withApi { api =>
      api
        .get("/lecturers/becker/schedule?termId=ws2026")
        .opt("entries")
        .asArray
        .value
        .map(_.hcursor.get[String]("eventId").toOption.value) should not contain "e18"
    }

    "die Schraffur auch ohne Einträge liefern" in withApi { api =>
      val res = api.get("/lecturers/vogt/schedule?termId=ws2026")
      res.opt("entries").asArray.value should have size 3 // e2, e12, e21
      res.opt("unavailable").asArray.value shouldBe empty
    }

    "bei unbekannter Person 404 liefern" in withApi { api =>
      val res = api.get("/lecturers/niemand/schedule")
      res.status shouldBe Status.NotFound
      res.str("code") shouldBe "NOT_FOUND"
    }
  }
