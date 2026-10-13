package app.route

import app.service.FleetService
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import protocol.Protocol
import protocol.ProtocolJson
import protocol.api.INVENTORY_SIZE
import protocol.messages.*

private const val HELLO_TIMEOUT_MS = 10_000L

/**
 * One connection per running turtle. The turtle opens it (turtles can't accept incoming connections) and sends a
 * [Hello] first; after that the server pushes seeds, renames, slot selections, refuels and commands down the same connection as
 * they happen, plus a heartbeat every [Protocol.HEARTBEAT_INTERVAL_SECONDS] so the turtle can tell the connection is
 * still alive.
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
        val heartbeat = launch {
            val text = ProtocolJson.encodeToString(HeartbeatMessage())
            while (true) {
                delay(Protocol.HEARTBEAT_INTERVAL_SECONDS * 1000)
                if (runCatching { send(Frame.Text(text)) }.isFailure) break
            }
        }
        try {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                handleMessage(fleet, turtleId, frame.readText())?.let { error ->
                    send(Frame.Text(ProtocolJson.encodeToString(ErrorMessage(error = error))))
                }
            }
        } finally {
            heartbeat.cancel()
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
            Protocol.MessageTypes.POSITION_UPDATE ->
                handle<PositionUpdate>(json, turtleId, { it.turtleId }) { fleet.recordPosition(it); null }

            Protocol.MessageTypes.LABEL_UPDATE ->
                handle<LabelUpdate>(json, turtleId, { it.turtleId }) { fleet.labelChanged(it); null }

            Protocol.MessageTypes.INVENTORY_UPDATE ->
                handle<InventoryUpdate>(json, turtleId, { it.turtleId }) { update ->
                    inventoryError(update) ?: run { fleet.recordInventory(update); null }
                }

            Protocol.MessageTypes.REFUEL_RESULT ->
                handle<RefuelResultUpdate>(json, turtleId, { it.turtleId }) { fleet.recordRefuelResult(it); null }

            Protocol.MessageTypes.EQUIPMENT_UPDATE ->
                handle<EquipmentUpdate>(json, turtleId, { it.turtleId }) { fleet.recordEquipment(it); null }

            Protocol.MessageTypes.COMMAND_RESULT ->
                handle<CommandResultUpdate>(json, turtleId, { it.turtleId }) { fleet.recordCommandResult(it); null }

            else -> "Unknown message type: $type"
        }
    }.getOrElse { "Invalid $type message: ${it.message}" }
}

/**
 * Decodes [json] as a [T] and passes it to [apply], unless it claims to come from a turtle other than the one on this
 * connection. Returns an error to send back, or null if it was handled. Decoding errors are thrown to the caller.
 */
private inline fun <reified T> handle(
    json: JsonObject,
    turtleId: Int,
    senderId: (T) -> Int,
    apply: (T) -> String?
): String? {
    val update = ProtocolJson.decodeFromJsonElement<T>(json)
    val claimedId = senderId(update)
    if (claimedId != turtleId) return "turtleId $claimedId does not match connection ($turtleId)"
    return apply(update)
}

/** Why an inventory update can't be stored, or null if all its slots are in range. */
private fun inventoryError(update: InventoryUpdate): String? {
    val slots = 1..INVENTORY_SIZE
    val inRange = update.selectedSlot in slots && update.slots.all { it.slot in slots }
    return if (inRange) null else "Inventory slots must be between 1 and $INVENTORY_SIZE"
}

private inline fun <reified T> decode(text: String): T = ProtocolJson.decodeFromString(text)
