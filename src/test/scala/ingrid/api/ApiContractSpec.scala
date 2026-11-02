package ingrid.api

import io.circe.Json
import org.http4s.Status
import ingrid.support.{ApiSpec, MockData}

/** Vertragstest gegen `raumplanung-mockdata.json`.
  *
  * Der Datensatz enthält fertige Beispielantworten und vorberechnete Zähler. Da das Backend diese
  * Felder zur Laufzeit berechnet, ist die Datei die unabhängige Sollvorgabe: stimmen die
  * berechneten Werte mit den von Hand gepflegten überein, sind Konfliktregeln, Raumwahl und
  * Zählwerte korrekt.
  */
final class ApiContractSpec extends ApiSpec:

  private def sameAs(actual: Json, expected: Json): Unit =
    MockData.canonical(actual) shouldBe MockData.canonical(expected)

  "Die Stammdaten-Endpunkte" should {
    "den Demodaten entsprechen" in withApi { api =>
      sameAs(api.get("/terms").json, MockData.section("terms"))
      sameAs(api.get("/timeslots").json, MockData.section("timeslots"))
      sameAs(api.get("/programs").json, MockData.section("programs"))
      sameAs(api.get("/stats?termId=ws2026").json, MockData.section("stats"))
    }
  }

  "Die berechneten Zähler der Listen" should {
    "den vorberechneten Werten der Demodaten entsprechen" in withApi { api =>
      withClue("usedSlots je Raum: ")(sameAs(api.get("/rooms?termId=ws2026").json, MockData.section("rooms")))
      withClue("eventCount je Person: ")(
        sameAs(api.get("/lecturers?termId=ws2026").json, MockData.section("lecturers"))
      )
      withClue("placedCount/eventCount/planId je Semester: ")(
        sameAs(api.get("/cohorts?termId=ws2026").json, MockData.section("cohorts"))
      )
      withClue("conflictCount je Plan: ")(
        sameAs(api.get("/plans?termId=ws2026").json, MockData.section("plans"))
      )
    }
  }

  "Die berechneten Konflikte" should {
    "für jede der 26 Veranstaltungen den Demodaten entsprechen" in withApi { api =>
      MockData.events should have size 26
      for expected <- MockData.events do
        val id = expected.hcursor.get[String]("id").toOption.value
        withClue(s"Veranstaltung $id: ") {
          val res = api.get(s"/events/$id")
          res.status shouldBe Status.Ok
          sameAs(res.json, expected)
        }
    }
  }

  "Die Beispielantworten des API-Pakets" should {
    "für die Raumbelegung stimmen" in withApi { api =>
      sameAs(
        api.get("/rooms/a-1.02/schedule?termId=ws2026").json,
        MockData.response("GET /rooms/a-1.02/schedule?termId=ws2026")
      )
    }

    "für die Wochenpläne der Dozierenden stimmen" in withApi { api =>
      sameAs(
        api.get("/lecturers/vogt/schedule?termId=ws2026").json,
        MockData.response("GET /lecturers/vogt/schedule?termId=ws2026")
      )
      sameAs(
        api.get("/lecturers/becker/schedule?termId=ws2026").json,
        MockData.response("GET /lecturers/becker/schedule?termId=ws2026")
      )
    }

    "für den Wochenplan des Semesters stimmen" in withApi { api =>
      sameAs(
        api.get("/cohorts/inf3/schedule?termId=ws2026").json,
        MockData.response("GET /cohorts/inf3/schedule?termId=ws2026")
      )
    }

    "für die Drag-&-Drop-Vorschau stimmen – inklusive Reihenfolge" in withApi { api =>
      val expected = MockData.response("""POST /placement-check {"eventId":"e4","termId":"ws2026"}""")
      val actual   = api.post("/placement-check", jsonOf("eventId" -> "e4".j, "termId" -> "ws2026".j)).json

      // Hier zählt die Reihenfolge der 30 Zellen und der Konflikte, deshalb exakter Vergleich.
      actual shouldBe expected
    }

    "für das Raum-Dropdown stimmen" in withApi { api =>
      sameAs(
        api.get("/rooms/available?eventId=e4&day=0&slot=1").json,
        MockData.response("GET /rooms/available?eventId=e4&day=0&slot=1")
      )
    }
  }

  "Die Veranstaltungslisten" should {
    "für Plan und Semester denselben Bestand liefern" in withApi { api =>
      val expected = Json.fromValues(
        MockData.events.filter(_.hcursor.get[String]("cohortId").toOption.contains("inf3"))
      )
      sameAs(api.get("/plans/p1/events").json, expected)
      sameAs(api.get("/cohorts/inf3/events").json, expected)
    }
  }
