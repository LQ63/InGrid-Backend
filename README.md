# INgrid Backend – Raumplanung (Prototyp)

Backend zum Raumplanungs-Prototypen der Fakultät Informatik, HTWG Konstanz.
Implementiert die 24 Endpunkte aus [`docs/message.txt`](docs/message.txt) bzw.
[`docs/raumplanung-api-paket/raumplanung-api.yaml`](docs/raumplanung-api-paket/raumplanung-api.yaml).

- Basis-URL: `http://localhost:8080/api/v1`, JSON
- Zeitraster: `day` = 0–4 (Mo–Fr), `slot` = 0–5 (08:00–09:30 … 17:30–19:00)

---

## Tech-Stack

| Komponente | Technologie |
| :--- | :--- |
| Sprache | Scala **3.9.0** (aktuelle LTS-Linie) |
| HTTP | http4s **0.23.38** (Ember-Server, funktionaler Stil auf Cats Effect) |
| JSON | circe **0.14.17** |
| Logging | Logback **1.6.6** |
| Build | sbt **1.13.1** |

Begründung des funktionalen Stils und der Modulstruktur: siehe
[ADR-0002](docs/ingrid-docs/docs/adr/0002-vertical-slice-architecture.md).

---

## Starten

```bash
sbt run
```

Danach z. B.:

```bash
curl http://localhost:8080/api/v1/stats?termId=ws2026
curl http://localhost:8080/api/v1/plans/p1/events
```

Konfiguration über Umgebungsvariablen (optional):

| Variable | Default | Zweck |
| :--- | :--- | :--- |
| `INGRID_PORT` | `8080` | HTTP-Port |
| `INGRID_HOST` | `0.0.0.0` | Bind-Adresse |

CORS ist für alle Origins offen, damit das Angular-Frontend von einem anderen Port zugreifen kann.

### Nutzung in IntelliJ

Das Scala-Plugin bringt einen eigenen sbt-Launcher mit – eine separate sbt-Installation ist für die
IDE nicht nötig:

1. `File → Settings → Plugins → Marketplace` → „Scala“ installieren, IntelliJ neu starten.
2. `File → Open` auf dieses Verzeichnis (bzw. direkt auf `build.sbt`) → Import als sbt-Projekt.
3. `View → Tool Windows → sbt shell` → dort `run`, `compile`, `test`.

#### „extracting structure failed: Build status: Error“

Häufigste Ursache: **sbt sperrt einen Build exklusiv** (`\\.\pipe\sbt-load…_lock`). Läuft parallel
ein `sbt` auf der Kommandozeile – etwa ein `sbt run` –, scheitert der Import von IntelliJ mit
`ServerAlreadyBootingException` und `error 5`. **Immer nur eine sbt-Instanz pro Build:** entweder
die IDE oder das Terminal, nicht beides.

Die konkrete Ursache steht in `View → Tool Windows → Build` (fehlgeschlagenen Knoten aufklappen) –
das Banner selbst verrät sie nicht. Weitere Kandidaten, wenn kein zweites sbt läuft: fehlendes oder
veraltetes Scala-Plugin, kein Projekt-SDK gesetzt, oder ein veraltetes `.idea`
(Projekt schliessen, `.idea/` löschen, über `build.sbt` neu öffnen).

#### JDK-Wahl

Scala 3.9 LTS unterstützt laut
[JDK-Kompatibilitätstabelle](https://docs.scala-lang.org/overviews/jdk-compatibility/overview.html)
JDK 8 bis 26; empfohlen sind die LTS-Versionen 17, 21 oder 25. Hier erprobt ist JDK 22. Das JDK muss
an drei Stellen konsistent gesetzt sein:

* `Settings → Build, Execution, Deployment → Build Tools → sbt` → *JVM*
* `Settings → Build, Execution, Deployment → Compiler → Scala Compiler → Scala Compile Server` → *JDK*
* `File → Project Structure → Project` → *Project SDK*

---

## Datenhaltung

Der Zustand liegt **im Speicher** (`Ref[IO, Snapshot]`, thread-sicher) und wird beim Start aus
[`src/main/resources/seed/raumplanung-mockdata.json`](src/main/resources/seed/raumplanung-mockdata.json)
geladen – dem Demodatensatz aus dem API-Paket (13 Räume, 8 Dozierende, 6 Semester, 3 Pläne,
26 Veranstaltungen). Änderungen über `POST`/`PUT`/`DELETE` gelten bis zum Neustart.

Die Datenbank-Entscheidung ist laut Dokumentation noch offen; der Zugriff ist in
`commons/Store.scala` gekapselt, sodass später eine Persistenz dahinter gelegt werden kann.

**Abgeleitete Felder werden nie gespeichert, sondern bei jedem Lesezugriff berechnet:**
`conflicts[]`, `usedSlots`, `placedCount`, `eventCount`, `conflictCount`, `eventCount` der
Dozierenden und `planId` der Semester. Die vorberechneten Werte aus den Demodaten werden beim
Einlesen verworfen – die berechneten Werte reproduzieren sie exakt.

---

## Modulstruktur (Vertical Slices, ADR-0002)

```text
src/main/scala/ingrid/
├── Main.scala                 # Serverstart, Routing-Komposition, CORS
├── commons/                   # Stammdaten & Basistypen, keine Geschäftslogik
│   ├── Model.scala            #   Entitäten, Enums, Zeitraster (Grid)
│   ├── Dto.scala              #   slice-übergreifende Wire-DTOs (Refs, Conflict, Schedule)
│   ├── Json.scala             #   Codecs der Basistypen
│   ├── Store.scala            #   Snapshot + thread-sicherer In-Memory-Store
│   ├── Seed.scala             #   Laden der Demodaten (API-Form -> Domänenform)
│   ├── Search.scala           #   Freitextsuche für `q`
│   ├── Clock.scala            #   Zeitstempel für `updatedAt`
│   └── ApiError.scala         #   fachlicher Fehler mit HTTP-Status
├── http/                      # Web-Querschnitt
│   ├── Params.scala           #   Query-Parameter-Decoder
│   └── Responses.scala        #   Fehler -> JSON (`Error`-Schema)
├── scheduling/                # Slice „Einplanung“ – Kern der Domäne
│   ├── ConflictRules.scala    #   reine Regeln: Eignung, Raumwahl, Konflikte
│   ├── Projections.scala      #   Domänenzustand -> DTOs, abgeleitete Zähler
│   ├── SchedulingService.scala#   einplanen / ausplanen / Vorschau
│   ├── SchedulingDto.scala
│   └── SchedulingRoutes.scala
├── masterdata/                # Slice „Stammdaten“: /terms /timeslots /programs /stats
├── rooms/                     # Slice „Räume“
├── lecturers/                 # Slice „Dozierende“
├── cohorts/                   # Slice „Semester“
├── plans/                     # Slice „Pläne“
└── events/                    # Slice „Veranstaltungen“
```

`ConflictRules` und `Projections` sind die öffentliche, seiteneffektfreie Schnittstelle des
Scheduling-Slices; die übrigen Slices greifen ausschliesslich hierüber auf die Planungslogik zu.
`commons` kennt keinen Slice.

---

## Fachliche Regeln

### Konflikte sind Soft Constraints

Konflikte verhindern das Speichern **nie** – sie werden nur in `conflicts[]` gemeldet
(vgl. [Konfliktmanagement](docs/ingrid-docs/docs/specification/conflicts-and-validation.md)).
Eine Veranstaltung gilt als „mit Konflikt“, sobald `conflicts[]` nicht leer ist.

| Code | Auslöser |
| :--- | :--- |
| `LECTURER_UNAVAILABLE` | `(day, slot)` liegt in `unavailable[]` der Dozentin / des Dozenten |
| `LECTURER_DOUBLE_BOOKED` | gleiche Person hält zeitgleich eine andere Veranstaltung |
| `COHORT_OVERLAP` | andere Veranstaltung desselben Semesters zur selben Zeit |
| `ROOM_DOUBLE_BOOKED` | Raum zur selben Zeit anderweitig belegt |
| `ROOM_TOO_SMALL` | `capacity < expectedParticipants` |
| `NO_ROOM` | eingeplant, aber kein passender Raum frei (`placement.roomId = null`) |

Die Reihenfolge in `conflicts[]` folgt dieser Tabelle. Kollisionen werden nur innerhalb
desselben Semesterzeitraums (`termId`) geprüft.

### Raumvergabe

`PUT /events/{id}/placement` ohne `roomId`:

1. Ein bereits zugewiesener Raum bleibt erhalten, solange er weiterhin passt (frei, geeignet,
   gross genug).
2. Sonst der Raum mit der **kleinsten ausreichenden Kapazität** (`capacity >= expectedParticipants`),
   der frei und für den Veranstaltungstyp geeignet ist.
3. Gibt es keinen, wird trotzdem eingeplant – mit `roomId: null` und `NO_ROOM`.

Mit `roomId` wird der gewählte Raum übernommen, auch wenn er Konflikte erzeugt.

**Eignung:** `lab`-Veranstaltungen nur in `lab` oder `pc_pool`; alle anderen nicht in `lab`.

**`GET /rooms/available`** liefert alle Räume mit Status, Priorität bei mehreren Gründen:
`occupied` > `too_small` > `unsuitable`.

---

## Getroffene Annahmen

Punkte, die die Spezifikation offen lässt:

1. **`stats.plansInProgress`** zählt alle Pläne des Zeitraums (auch `pending_approval`) – nur so
   ergibt sich der Wert `3` aus den Demodaten.
2. **`POST /plans` mit `copyFromCatalog: true`**: Hat das Semester schon Veranstaltungen, sind das
   die zu übernehmenden, es wird nichts kopiert. Ist es neu und leer, dient dasselbe Fachsemester
   desselben Studiengangs aus einem anderen Zeitraum als Schablone (Vorjahres-Rollover, vgl.
   [`semester_rollover.feature`](docs/ingrid-docs/docs/specification/features/semester_rollover.feature));
   Titel, Typ, Dozent:in, Teilnehmerzahl und die Platzierung als Ausgangsbasis werden übernommen.
3. **Ein Semester hat höchstens einen Plan** – ein zweiter `POST /plans` auf dasselbe Semester
   endet mit `409`.
4. **Nachrichtentexte** der Konflikte folgen dem Wortlaut der Demodaten. Für
   `ROOM_DOUBLE_BOOKED` und `NO_ROOM` enthalten die Demodaten keine Beispiele; die Texte sind im
   gleichen Stil ergänzt.
5. **IDs** neuer Objekte folgen dem Schema der Demodaten: Semester `programId + semester`
   (z. B. `ds3`), Pläne `p<n>`, Veranstaltungen `e<n>`.
6. **`GET /cohorts/{id}/schedule`** ignoriert `termId` – das Semester bestimmt den Zeitraum bereits.

---

## Tests & Qualitätssicherung

```bash
sbt test                                  # 213 Tests
sbt "clean; coverage; test; coverageReport"  # Abdeckung messen
```

Bericht danach unter `target/scala-3.9.0/scoverage-report/index.html`.

**Stand:** 213 Tests, alle grün. **Statement-Coverage 99,67 %, Branch-Coverage 100 %.**

Nicht abgedeckt sind ausschliesslich die drei Statements in `Main.run` – der `IOApp`-Einstiegspunkt,
der die echte Prozessumgebung liest. Er liesse sich nur über einen eigenen Subprozess abdecken; der
eigentliche Start ist stattdessen über `Main.serveForever` auf einem freien Port getestet.

### Aufbau der Testsuite

| Spec | Prüft |
| :--- | :--- |
| `ConflictRulesSpec` | alle 6 Konfliktcodes inkl. Meldungstexte, Raumeignung, Raumwahl, Semester-Abgrenzung |
| `ProjectionsSpec` | alle abgeleiteten Zähler gegen die vorberechneten Werte der Demodaten |
| `CommonsSpec` | Demodaten-Mapping, Snapshot-Indizes, Store-Atomarität und Rollback, 50 parallele Änderungen |
| `ApiContractSpec` | **Vertragstest**: alle 26 Veranstaltungen und alle 6 Beispielantworten aus `raumplanung-mockdata.json` |
| `BootstrapSpec` | echter Serverstart über HTTP, CORS-Header, Konfiguration, Fehler beim Laden der Demodaten |
| `*RoutesSpec` | je Slice: Filter, Suche, Statuscodes, Validierung, Fortschreiben der Zähler |
| `ErrorHandlingSpec` | JSON-Fehler bei unbekannten Pfaden, falschen Methoden und defekten Bodies |

Der `ApiContractSpec` ist der wichtigste Test: da das Backend `conflicts[]`, `usedSlots`,
`placedCount` und `conflictCount` zur Laufzeit berechnet, während die Demodaten sie von Hand
pflegen, ist die Übereinstimmung ein unabhängiger Nachweis der Planungslogik.

**Noch offen** (gemäss [ADR-0003](docs/ingrid-docs/docs/adr/0003-backend-testing-and-quality-tooling.md)
und [ADR-0005](docs/ingrid-docs/docs/adr/0005-archunit-architecture-testing.md)):
Cucumber-Anbindung der vorhandenen `.feature`-Dateien, Stryker4s, Scalafix und ArchUnit-Regeln.
`.scalafmt.conf` und `sbt-scoverage` liegen vor.

Die `.feature`-Dateien beschreiben eine umfangreichere Domäne als diese Prototyp-API
(Checklisten, Modulvertretung, Forschungssemester, Blockveranstaltungen). Ihre Anbindung setzt
voraus, dass entschieden wird, welche dieser Fachlichkeit das Backend abbilden soll.
