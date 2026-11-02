package ingrid.api

import org.http4s.Status
import ingrid.support.ApiSpec

/** `GET /rooms`, `/rooms/available`, `/rooms/{id}/schedule`. */
final class RoomRoutesSpec extends ApiSpec:

  "GET /rooms" should {
    "alle Räume mit Belegungszahlen liefern" in withApi { api =>
      val res = api.get("/rooms?termId=ws2026")
      res.status shouldBe Status.Ok
      res.items should have size 13

      val room = res.items.find(_.hcursor.get[String]("id").toOption.contains("a-1.02")).value
      room.hcursor.get[String]("name").toOption shouldBe Some("A 1.02")
      room.hcursor.get[String]("type").toOption shouldBe Some("lecture_hall")
      room.hcursor.get[Int]("capacity").toOption shouldBe Some(150)
      room.hcursor.get[Int]("usedSlots").toOption shouldBe Some(5)
      room.hcursor.get[Int]("totalSlots").toOption shouldBe Some(30)
      room.hcursor.get[List[String]]("equipment").toOption shouldBe Some(List("Beamer", "Mikrofon"))
    }

    "nach Gebäude filtern" in withApi { api =>
      val res = api.get("/rooms?building=B")
      res.items should have size 4
      res.ids.foreach(_ should startWith("b-"))
    }

    "Gebäude unabhängig von Gross-/Kleinschreibung filtern" in withApi { api =>
      api.get("/rooms?building=b").items should have size 4
    }

    "nach Raumtyp filtern" in withApi { api =>
      api.get("/rooms?type=lab").ids shouldBe Vector("b-1.20", "b-1.21")
      api.get("/rooms?type=pc_pool").ids shouldBe Vector("b-0.08", "b-0.09")
      api.get("/rooms?type=lecture_hall").items should have size 4
      api.get("/rooms?type=seminar_room").items should have size 5
    }

    "nach Mindestkapazität filtern" in withApi { api =>
      val res = api.get("/rooms?minCapacity=150")
      res.ids shouldBe Vector("c-0.01", "a-1.01", "a-1.02")
    }

    "per Freitext suchen" in withApi { api =>
      api.get("/rooms?q=A%202.1").items should have size 3
      api.get("/rooms?q=mikrofon").items.size should be > 0
      api.get("/rooms?q=gibtsnicht").items shouldBe empty
    }

    "Filter kombinieren" in withApi { api =>
      api.get("/rooms?building=A&type=seminar_room&minCapacity=30").ids shouldBe
        Vector("a-2.14", "a-2.15")
    }

    "ungültigen Raumtyp mit 400 ablehnen" in withApi { api =>
      val res = api.get("/rooms?type=unsinn")
      res.status shouldBe Status.BadRequest
      res.str("code") shouldBe "VALIDATION_ERROR"
    }

    "nicht-numerische Mindestkapazität mit 400 ablehnen" in withApi { api =>
      api.get("/rooms?minCapacity=viele").status shouldBe Status.BadRequest
    }
  }

  "GET /rooms/{id}/schedule" should {
    "die Belegung des Raums liefern" in withApi { api =>
      val res = api.get("/rooms/a-1.02/schedule?termId=ws2026")
      res.status shouldBe Status.Ok
      val entries = res.opt("entries").asArray.value
      entries should have size 5
      entries.map(_.hcursor.get[String]("eventId").toOption.value).sorted shouldBe
        Vector("e1", "e15", "e20", "e3", "e8").sorted
    }

    "für einen leeren Raum eine leere Liste liefern" in withApi { api =>
      api.get("/rooms/a-2.15/schedule?termId=ws2026").opt("entries").asArray.value shouldBe empty
    }

    "bei unbekanntem Raum 404 liefern" in withApi { api =>
      val res = api.get("/rooms/x-9.99/schedule")
      res.status shouldBe Status.NotFound
      res.str("code") shouldBe "NOT_FOUND"
    }
  }

  "GET /rooms/available" should {
    "alle Räume mit Status für den Slot liefern" in withApi { api =>
      // e4: Übung, 35 TN, Mo Slot 1 (a-1.02 ist dort durch e1 belegt).
      val res = api.get("/rooms/available?eventId=e4&day=0&slot=1")
      res.status shouldBe Status.Ok
      res.items should have size 13

      def statusOf(id: String): String =
        res.items
          .find(_.hcursor.get[String]("id").toOption.contains(id))
          .value
          .hcursor
          .get[String]("status")
          .toOption
          .value

      statusOf("a-1.02") shouldBe "occupied"  // durch e1 belegt
      statusOf("a-2.15") shouldBe "too_small" // 30 < 35
      statusOf("b-1.20") shouldBe "too_small" // Labor, 20 Plätze -> zu klein schlägt ungeeignet
      statusOf("b-0.08") shouldBe "free"      // PC-Pool, 36 Plätze
      statusOf("c-0.01") shouldBe "free"
    }

    "ungeeignet melden, wenn der Raumtyp nicht passt" in withApi { api =>
      // e7: Labor, 20 TN -> Seminarräume sind gross genug, aber ungeeignet.
      val res = api.get("/rooms/available?eventId=e7&day=0&slot=0")
      res.items
        .find(_.hcursor.get[String]("id").toOption.contains("a-2.15"))
        .value
        .hcursor
        .get[String]("status")
        .toOption shouldBe Some("unsuitable")
      res.items
        .find(_.hcursor.get[String]("id").toOption.contains("b-1.20"))
        .value
        .hcursor
        .get[String]("status")
        .toOption shouldBe Some("free")
    }

    "bei unbekannter Veranstaltung 404 liefern" in withApi { api =>
      api.get("/rooms/available?eventId=e99&day=0&slot=0").status shouldBe Status.NotFound
    }

    "ungültige Slots mit 400 ablehnen" in withApi { api =>
      api.get("/rooms/available?eventId=e4&day=0&slot=9").status shouldBe Status.BadRequest
      api.get("/rooms/available?eventId=e4&day=9&slot=0").status shouldBe Status.BadRequest
    }

    "fehlende Pflichtparameter mit 400 ablehnen" in withApi { api =>
      for query <- List("?eventId=e4", "?eventId=e4&day=0", "?day=0&slot=0", "") do
        withClue(s"GET /rooms/available$query: ") {
          val res = api.get(s"/rooms/available$query")
          res.status shouldBe Status.BadRequest
          res.str("code") shouldBe "VALIDATION_ERROR"
        }
    }

    "nicht-numerische Slots mit 400 ablehnen" in withApi { api =>
      api.get("/rooms/available?eventId=e4&day=montag&slot=0").status shouldBe Status.BadRequest
    }
  }
