package app.route

import app.service.StationResult
import app.service.StationService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import protocol.Protocol
import protocol.api.ApiError
import protocol.api.StationRequest
import protocol.api.StationTypes

private const val INVALID_STATION_ID = "Invalid station id"

/** Stations the dashboard sets up for turtles to refuel, drop items off and pick them up. */
fun Route.stationRoutes(stations: StationService) {
    route(Protocol.Paths.API) {
        // Every station, or only one type with ?type=
        get(Protocol.Paths.STATIONS) {
            val type = call.request.queryParameters["type"]
            if (type != null && type !in StationTypes.ALL) {
                return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("type must be one of ${StationTypes.ALL.joinToString()}, got '$type'")
                )
            }
            call.respond(stations.all(type))
        }

        get(Protocol.Paths.STATION) {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError(INVALID_STATION_ID))

            val station = stations.get(id)
                ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("Unknown station: $id"))

            call.respond(station)
        }

        post(Protocol.Paths.STATIONS) {
            val request = runCatching { call.receive<StationRequest>() }.getOrElse {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid station body"))
            }
            respond(call, stations.create(request), HttpStatusCode.Created)
        }

        // Replaces the whole station; its id stays the same
        put(Protocol.Paths.STATION) {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@put call.respond(HttpStatusCode.BadRequest, ApiError(INVALID_STATION_ID))

            val request = runCatching { call.receive<StationRequest>() }.getOrElse {
                return@put call.respond(HttpStatusCode.BadRequest, ApiError("Invalid station body"))
            }
            respond(call, stations.replace(id, request), HttpStatusCode.OK)
        }

        delete(Protocol.Paths.STATION) {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, ApiError(INVALID_STATION_ID))

            if (!stations.delete(id)) {
                return@delete call.respond(HttpStatusCode.NotFound, ApiError("Unknown station: $id"))
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private suspend fun respond(call: ApplicationCall, result: StationResult, savedStatus: HttpStatusCode) =
    when (result) {
        is StationResult.Saved -> call.respond(savedStatus, result.station)
        is StationResult.Invalid -> call.respond(HttpStatusCode.BadRequest, ApiError(result.error))
        is StationResult.Overlaps -> call.respond(HttpStatusCode.Conflict, ApiError(result.error))
        StationResult.NotFound -> call.respond(HttpStatusCode.NotFound, ApiError("Unknown station"))
    }
