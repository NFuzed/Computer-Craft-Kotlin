package app.route

import app.service.FleetService
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import protocol.Protocol
import protocol.ProtocolJson
import protocol.api.INVENTORY_SIZE
import protocol.messages.*

private const val HELLO_TIMEOUT_MS = 10_000L

/**
 * One connection per running turtle. The turtle opens it (turtles can't accept incoming connections) and sends a
 * [Hello] first; after that the server pushes seeds, renames and slot selections down the same connection as they happen.
 */
fun Route.turtleSocketRoutes(fleet: FleetService) {
    webSocket(Protocol.Paths.WS_TURTLE) {
        val hello = withTimeoutOrNull(HELLO_TIMEOUT_MS) {
            runCatching { (incoming.receive() as? Frame.Text)?.let { decode<Hello>(it.readText()) } }.getOrNull()
        }
        if (hello == null || hello.type != Protocol.MessageTypes.HELLO) {
            return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Expected a hello message first"))
        }

        val turtleId = hello.turtleId
        fleet.connect(hello, this)
        try {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                handleMessage(fleet, turtleId, frame.readText())?.let { error ->
                    send(Frame.Text(ProtocolJson.encodeToString(ErrorMessage(error = error))))
                }
            }
        } finally {
            fleet.disconnect(turtleId, this)
        }
    }
}

/** Handles one message from a connected turtle. Returns an error to send back, or null if it was handled. */
private fun handleMessage(fleet: FleetService, turtleId: Int, text: String): String? {
    val json = runCatching { ProtocolJson.parseToJsonElement(text).jsonObject }.getOrNull()
        ?: return "Message is not a JSON object"
    val type = json["type"]?.jsonPrimitive?.contentOrNull

    return runCatching {
        when (type) {
            Protocol.MessageTypes.POSITION_UPDATE -> {
                val update = ProtocolJson.decodeFromJsonElement<PositionUpdate>(json)
                if (update.turtleId != turtleId) return "turtleId ${update.turtleId} does not match connection ($turtleId)"
                fleet.recordPosition(update)
            }

            Protocol.MessageTypes.LABEL_UPDATE -> {
                val update = ProtocolJson.decodeFromJsonElement<LabelUpdate>(json)
                if (update.turtleId != turtleId) return "turtleId ${update.turtleId} does not match connection ($turtleId)"
                fleet.labelChanged(update)
            }

            Protocol.MessageTypes.INVENTORY_UPDATE -> {
                val update = ProtocolJson.decodeFromJsonElement<InventoryUpdate>(json)
                if (update.turtleId != turtleId) return "turtleId ${update.turtleId} does not match connection ($turtleId)"
                val slots = 1..INVENTORY_SIZE
                if (update.selectedSlot !in slots || update.slots.any { it.slot !in slots }) {
                    return "Inventory slots must be between 1 and $INVENTORY_SIZE"
                }
                fleet.recordInventory(update)
            }

            else -> return "Unknown message type: $type"
        }
        null
    }.getOrElse { "Invalid $type message: ${it.message}" }
}

private inline fun <reified T> decode(text: String): T = ProtocolJson.decodeFromString(text)
