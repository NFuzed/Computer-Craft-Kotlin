package app.route

import app.service.FleetService
import app.store.BlockPos
import app.store.MapId
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
        turtleRoutes(fleet)
        inventoryRoutes(fleet)
        commandRoutes(fleet)
        mapRoutes(fleet)
    }
}

/** A turtle's status, and the position seeds and renames the dashboard queues for it. */
private fun Route.turtleRoutes(fleet: FleetService) {
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

/** A turtle's inventory, and the slot selections and refuels the dashboard sends it. */
private fun Route.inventoryRoutes(fleet: FleetService) {
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
}

/** Single commands (move, turn, dig, place...) the dashboard sends a turtle. */
private fun Route.commandRoutes(fleet: FleetService) {
    // Dashboard makes a turtle move, turn, dig, place...; the outcome shows up as the turtle's lastCommand
    post(Protocol.Paths.TURTLE_COMMANDS) {
        val id = call.parameters["id"]?.toIntOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError(INVALID_TURTLE_ID))

        val request = runCatching { call.receive<CommandRequest>() }.getOrElse {
            return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid command body"))
        }

        if (request.command !in Commands.ALL) {
            return@post call.respond(
                HttpStatusCode.BadRequest,
                ApiError("command must be one of ${Commands.ALL.joinToString()}, got '${request.command}'")
            )
        }

        val command = fleet.sendCommand(id, request.command)
            ?: return@post call.respond(HttpStatusCode.Conflict, ApiError("Turtle $id is not connected"))

        call.respond(HttpStatusCode.Accepted, command)
    }
}

/** The world map the turtles have built up from what they've seen. */
private fun Route.mapRoutes(fleet: FleetService) {
    // Known blocks in a box: minX..maxX, minY..maxY, minZ..maxZ (inclusive), in the map for world and dimension
    // (each optional, matching what the turtles were seeded with)
    get(Protocol.Paths.MAP_BLOCKS) {
        val params = call.request.queryParameters
        val bounds = listOf("minX", "minY", "minZ", "maxX", "maxY", "maxZ").map { name ->
            params[name]?.toIntOrNull() ?: return@get call.respond(
                HttpStatusCode.BadRequest,
                ApiError("$name is required and must be an integer")
            )
        }
        val min = BlockPos(bounds[0], bounds[1], bounds[2])
        val max = BlockPos(bounds[3], bounds[4], bounds[5])

        val spans = listOf(max.x.toLong() - min.x, max.y.toLong() - min.y, max.z.toLong() - min.z)
        if (spans.any { it < 0 }) {
            return@get call.respond(HttpStatusCode.BadRequest, ApiError("Each min must be at most its max"))
        }
        if (spans.any { it >= MAX_MAP_QUERY_SPAN }) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                ApiError("The box can be at most $MAX_MAP_QUERY_SPAN blocks along each axis")
            )
        }

        call.respond(fleet.mapRegion(MapId(params["world"], params["dimension"]), min, max))
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
