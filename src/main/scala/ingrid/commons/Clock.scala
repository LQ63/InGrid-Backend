package ingrid.commons

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Zeitstempel im Format der API (`updatedAt`, ISO 8601 mit Offset). */
object Clock:
  def nowIso: String =
    OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
