package ingrid.commons

import cats.effect.{IO, Ref}

/** Vollständiger Zustand des Prototyps. Stammdaten sind unveränderlich, veränderlich sind
  * `cohorts`, `plans` und `events`.
  */
final case class Snapshot(
    terms: List[Term],
    timeslots: List[Timeslot],
    programs: List[Program],
    rooms: List[Room],
    lecturers: List[Lecturer],
    cohorts: List[Cohort],
    plans: List[Plan],
    events: List[Event]
):
  lazy val programById: Map[String, Program]   = programs.map(p => p.id -> p).toMap
  lazy val roomById: Map[String, Room]         = rooms.map(r => r.id -> r).toMap
  lazy val lecturerById: Map[String, Lecturer] = lecturers.map(l => l.id -> l).toMap
  lazy val cohortById: Map[String, Cohort]     = cohorts.map(c => c.id -> c).toMap
  lazy val planById: Map[String, Plan]         = plans.map(p => p.id -> p).toMap
  lazy val eventById: Map[String, Event]       = events.map(e => e.id -> e).toMap
  lazy val termById: Map[String, Term]         = terms.map(t => t.id -> t).toMap

  /** Ein Semester hat höchstens einen Plan. */
  lazy val planByCohortId: Map[String, Plan] = plans.map(p => p.cohortId -> p).toMap

  lazy val eventsByCohortId: Map[String, List[Event]] = events.groupBy(_.cohortId)

  def term(cohortId: String): Option[String]  = cohortById.get(cohortId).map(_.termId)
  def termOf(e: Event): Option[String]        = term(e.cohortId)
  def timeslot(slot: Int): Option[Timeslot]   = timeslots.find(_.slot == slot)

  def requireRoom(id: String): Room = roomById.getOrElse(id, throw ApiError.notFound(s"Raum $id nicht gefunden"))
  def requireLecturer(id: String): Lecturer =
    lecturerById.getOrElse(id, throw ApiError.notFound(s"Dozent:in $id nicht gefunden"))
  def requireCohort(id: String): Cohort =
    cohortById.getOrElse(id, throw ApiError.notFound(s"Semester $id nicht gefunden"))
  def requirePlan(id: String): Plan =
    planById.getOrElse(id, throw ApiError.notFound(s"Plan $id nicht gefunden"))
  def requireEvent(id: String): Event =
    eventById.getOrElse(id, throw ApiError.notFound(s"Veranstaltung $id nicht gefunden"))
  def requireProgram(id: String): Program =
    programById.getOrElse(id, throw ApiError.notFound(s"Studiengang $id nicht gefunden"))
  def requireTerm(id: String): Term =
    termById.getOrElse(id, throw ApiError.notFound(s"Semester $id nicht gefunden"))

  def withEvents(es: List[Event]): Snapshot  = copy(events = es)
  def withCohorts(cs: List[Cohort]): Snapshot = copy(cohorts = cs)
  def withPlans(ps: List[Plan]): Snapshot    = copy(plans = ps)

  def replaceEvent(e: Event): Snapshot =
    withEvents(events.map(x => if x.id == e.id then e else x))

/** Thread-sicherer In-Memory-Store. Die DB-Entscheidung ist laut Doku noch offen – der Store
  * kapselt den Zugriff, damit später eine Persistenz dahinter gelegt werden kann.
  */
final class Store(ref: Ref[IO, Snapshot]):

  def read: IO[Snapshot] = ref.get

  /** Lesender Zugriff, der fachliche Fehler als `ApiError` werfen darf. */
  def query[A](f: Snapshot => A): IO[A] = ref.get.flatMap(s => IO(f(s)))

  /** Atomares Lesen-Ändern-Schreiben. `f` darf `ApiError` werfen; dann bleibt der Zustand unverändert. */
  def mutate[A](f: Snapshot => (Snapshot, A)): IO[A] =
    ref
      .modify { s =>
        try
          val (next, a) = f(s)
          (next, IO.pure(a))
        catch case e: ApiError => (s, IO.raiseError[A](e))
      }
      .flatten

object Store:
  def seeded: IO[Store] = Seed.load.flatMap(Ref.of[IO, Snapshot]).map(new Store(_))
