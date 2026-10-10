package app.route

import app.service.FleetService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import protocol.Protocol
import protocol.api.*

private const val INVALID_TURTLE_ID = "Invalid turtle id"

fun Route.apiRoutes(fleet: FleetService) {
    route(Protocol.Paths.API) {
        get(Protocol.Paths.TURTLES) {
            call.respond(fleet.allStatuses())
        }

        get(Protocol.Paths.TURTLE) {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError(INVALID_TURTLE_ID))

            val status = fleet.status(id)
                ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("Unknown turtle: $id"))

            call.respond(status)
        }

        // Dashboard (and later commander turtles) set where a turtle is; pushed now if the turtle is connected,
        // otherwise when it next connects
        post(Protocol.Paths.TURTLE_SEED) {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError(INVALID_TURTLE_ID))

            val request = runCatching { call.receive<SeedRequest>() }.getOrElse {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid seed body"))
            }

            if (request.heading !in HEADINGS) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("heading must be one of ${HEADINGS.joinToString()}, got '${request.heading}'")
                )
            }

            call.respond(HttpStatusCode.Accepted, fleet.seed(id, request))
        }

        // The last inventory the turtle reported; it reports again whenever the inventory changes
        get(Protocol.Paths.TURTLE_INVENTORY) {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError(INVALID_TURTLE_ID))

            val inventory = fleet.inventory(id)
                ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("No inventory reported for turtle $id"))

            call.respond(inventory)
        }

        // Dashboard picks the turtle's selected slot; the new selection shows up once the turtle reports its inventory
        post(Protocol.Paths.TURTLE_SELECTED_SLOT) {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError(INVALID_TURTLE_ID))

            val request = runCatching { call.receive<SelectSlotRequest>() }.getOrElse {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid select slot body"))
            }

            if (request.slot !in 1..INVENTORY_SIZE) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("slot must be between 1 and $INVENTORY_SIZE, got ${request.slot}")
                )
            }

            if (!fleet.selectSlot(id, request.slot)) {
                return@post call.respond(HttpStatusCode.Conflict, ApiError("Turtle $id is not connected"))
            }

            call.respond(HttpStatusCode.Accepted, request)
        }

        // Dashboard makes a turtle burn fuel from its selected slot; the outcome shows up as the inventory's lastRefuel
        post(Protocol.Paths.TURTLE_REFUEL) {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError(INVALID_TURTLE_ID))

            val request = runCatching { call.receive<RefuelRequest>() }.getOrElse {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid refuel body"))
            }

            if (request.count != null && request.count !in 1..MAX_REFUEL_COUNT) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("count must be between 1 and $MAX_REFUEL_COUNT, got ${request.count}")
                )
            }

            val command = fleet.refuel(id, request.count)
                ?: return@post call.respond(HttpStatusCode.Conflict, ApiError("Turtle $id is not connected"))

            call.respond(HttpStatusCode.Accepted, command)
        }

        // Dashboard renames a turtle; the turtle sets its own label when the rename reaches it
        put(Protocol.Paths.TURTLE_LABEL) {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@put call.respond(HttpStatusCode.BadRequest, ApiError(INVALID_TURTLE_ID))

            val request = runCatching { call.receive<LabelRequest>() }.getOrElse {
                return@put call.respond(HttpStatusCode.BadRequest, ApiError("Invalid label body"))
            }

            val label = request.label?.trim()?.takeIf { it.isNotEmpty() }
            labelError(label)?.let {
                return@put call.respond(HttpStatusCode.BadRequest, ApiError(it))
            }

            call.respond(HttpStatusCode.Accepted, fleet.rename(id, label))
        }
    }
}

/** Returns why [label] can't be used as a turtle label, or null if it's fine (null clears the label). */
private fun labelError(label: String?): String? = when {
    label == null -> null
    label.length > MAX_LABEL_LENGTH -> "label must be at most $MAX_LABEL_LENGTH characters, got ${label.length}"
    // ComputerCraft drops other characters, so the turtle's label would never match and the rename would repeat
    label.any { it !in ' '..'~' } -> "label may only contain printable ASCII characters"
    else -> null
}
