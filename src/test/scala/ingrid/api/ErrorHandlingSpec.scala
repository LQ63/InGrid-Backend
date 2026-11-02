package ingrid.api

import org.http4s.Status
import ingrid.support.ApiSpec

/** Jede Antwort der API ist JSON im `Error`-Schema – auch Routing- und Body-Fehler. */
final class ErrorHandlingSpec extends ApiSpec:

  "Unbekannte Pfade" should {
    "als JSON-404 antworten" in withApi { api =>
      val res = api.get("/gibtsnicht")
      res.status shouldBe Status.NotFound
      res.str("code") shouldBe "NOT_FOUND"
      res.str("message") should include("/api/v1/gibtsnicht")
    }

    "auch unterhalb bekannter Präfixe als JSON antworten" in withApi { api =>
      val res = api.get("/rooms/a-1.02/unsinn")
      res.status shouldBe Status.NotFound
      res.str("code") shouldBe "NOT_FOUND"
    }
  }

  "Nicht erlaubte Methoden" should {
    "mit einem JSON-Fehler antworten" in withApi { api =>
      val res = api.delete("/terms")
      res.status.code should be >= 400
      res.str("code") shouldBe "NOT_FOUND"
    }
  }

  "Fehlerhafte Request-Bodies" should {
    "kaputtes JSON mit 400 ablehnen" in withApi { api =>
      val res = api.postRaw("/events", "{das ist kein json")
      res.status shouldBe Status.BadRequest
      res.str("code") shouldBe "VALIDATION_ERROR"
    }

    "leeren Body mit 400 ablehnen" in withApi { api =>
      api.postRaw("/events", "").status shouldBe Status.BadRequest
    }

    "falsche Feldtypen mit 400 ablehnen" in withApi { api =>
      val res = api.postRaw(
        "/events",
        """{"title":"X","type":"lecture","lecturerId":"vogt","cohortId":"inf3","expectedParticipants":"viele"}"""
      )
      res.status shouldBe Status.BadRequest
      res.str("code") shouldBe "VALIDATION_ERROR"
    }

    "ein JSON-Array statt eines Objekts mit 400 ablehnen" in withApi { api =>
      api.postRaw("/events", "[]").status shouldBe Status.BadRequest
    }
  }

  "Fehlerantworten" should {
    "immer code und message enthalten" in withApi { api =>
      for (path, expected) <- List(
          "/events/e99"                 -> "NOT_FOUND",
          "/plans/p99"                  -> "NOT_FOUND",
          "/cohorts/x"                  -> "NOT_FOUND",
          "/lecturers/x/schedule"       -> "NOT_FOUND",
          "/rooms?type=unsinn"          -> "VALIDATION_ERROR",
          "/rooms/available?eventId=e4&day=0&slot=9" -> "VALIDATION_ERROR"
        )
      do
        withClue(s"GET $path: ") {
          val res = api.get(path)
          res.str("code") shouldBe expected
          res.str("message") should not be empty
        }
    }
  }
