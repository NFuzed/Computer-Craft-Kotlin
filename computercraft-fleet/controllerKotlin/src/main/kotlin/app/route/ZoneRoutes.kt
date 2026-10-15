package app.route

import app.service.ZoneResult
import app.service.ZoneService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import protocol.Protocol
import protocol.api.ApiError
import protocol.api.ZoneRequest
import protocol.api.ZoneTypes

private const val INVALID_ZONE_ID = "Invalid zone id"

/** No-dig zones and quarries the dashboard marks out on its map. */
fun Route.zoneRoutes(zones: ZoneService) {
    route(Protocol.Paths.API) {
        // Every zone, or only one type with ?type=
        get(Protocol.Paths.ZONES) {
            val type = call.request.queryParameters["type"]
            if (type != null && type !in ZoneTypes.ALL) {
                return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("type must be one of ${ZoneTypes.ALL.joinToString()}, got '$type'")
                )
            }
            call.respond(zones.all(type))
        }

        get(Protocol.Paths.ZONE) {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError(INVALID_ZONE_ID))

            val zone = zones.get(id)
                ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("Unknown zone: $id"))

            call.respond(zone)
        }

        post(Protocol.Paths.ZONES) {
            val request = runCatching { call.receive<ZoneRequest>() }.getOrElse {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid zone body"))
            }
            respond(call, zones.create(request), HttpStatusCode.Created)
        }

        // Replaces the whole zone; its id stays the same
        put(Protocol.Paths.ZONE) {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@put call.respond(HttpStatusCode.BadRequest, ApiError(INVALID_ZONE_ID))

            val request = runCatching { call.receive<ZoneRequest>() }.getOrElse {
                return@put call.respond(HttpStatusCode.BadRequest, ApiError("Invalid zone body"))
            }
            respond(call, zones.replace(id, request), HttpStatusCode.OK)
        }

        delete(Protocol.Paths.ZONE) {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, ApiError(INVALID_ZONE_ID))

            if (!zones.delete(id)) {
                return@delete call.respond(HttpStatusCode.NotFound, ApiError("Unknown zone: $id"))
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private suspend fun respond(call: ApplicationCall, result: ZoneResult, savedStatus: HttpStatusCode) =
    when (result) {
        is ZoneResult.Saved -> call.respond(savedStatus, result.zone)
        is ZoneResult.Invalid -> call.respond(HttpStatusCode.BadRequest, ApiError(result.error))
        ZoneResult.NotFound -> call.respond(HttpStatusCode.NotFound, ApiError("Unknown zone"))
    }
