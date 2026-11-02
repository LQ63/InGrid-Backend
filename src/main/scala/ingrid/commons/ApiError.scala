package ingrid.commons

/** Fachlicher Fehler mit HTTP-Status. Der Status bleibt absichtlich ein `Int`, damit `commons`
  * frei von Web-Framework-Abhängigkeiten bleibt; die Übersetzung passiert in `ingrid.http`.
  */
final case class ApiError(status: Int, code: String, message: String)
    extends RuntimeException(message):
  override def fillInStackTrace(): Throwable = this

object ApiError:
  def notFound(message: String): ApiError   = ApiError(404, "NOT_FOUND", message)
  def badRequest(message: String): ApiError = ApiError(400, "VALIDATION_ERROR", message)
  def conflict(message: String): ApiError   = ApiError(409, "CONFLICT", message)
