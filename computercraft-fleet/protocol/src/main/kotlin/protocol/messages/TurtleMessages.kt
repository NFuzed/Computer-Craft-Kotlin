package protocol.messages

import kotlinx.serialization.Serializable
import protocol.Protocol
import protocol.api.EquippedUpgrade
import protocol.api.InventorySlot
import protocol.api.PositionSeed
import protocol.api.TurtleFuel

// Messages sent over /ws/turtle as JSON text frames. Every message has a `type`; see [Protocol.MessageTypes].
// PositionUpdate (turtle → server) is in its own file.

/** First message a turtle sends after connecting. Tells the server who it is and what it is called. */
@Serializable
data class Hello(
    val v: Int = Protocol.VERSION,
    val type: String = Protocol.MessageTypes.HELLO,
    val turtleId: Int,
    val label: String? = null       // os.getComputerLabel(), null if unset
)

/** Sent by a turtle after applying a rename, with the label it now has. Clears the matching pending rename. */
@Serializable
data class LabelUpdate(
    val v: Int = Protocol.VERSION,
    val type: String = Protocol.MessageTypes.LABEL_UPDATE,
    val turtleId: Int,
    val label: String? = null
)

/**
 * Sent by a turtle whenever its inventory or selected slot changes, and after each connect. Replaces the
 * inventory the server has stored for it.
 */
@Serializable
data class InventoryUpdate(
    val v: Int = Protocol.VERSION,
    val type: String = Protocol.MessageTypes.INVENTORY_UPDATE,
    val turtleId: Int,
    val selectedSlot: Int,
    val slots: List<InventorySlot>,
    val fuel: TurtleFuel? = null,
    val ts: Long = System.currentTimeMillis() / 1000
)

/** Sent by a turtle after running a [RefuelMessage], followed by an [InventoryUpdate] with its new fuel level. */
@Serializable
data class RefuelResultUpdate(
    val v: Int = Protocol.VERSION,
    val type: String = Protocol.MessageTypes.REFUEL_RESULT,
    val turtleId: Int,
    val refuelId: Long,
    val refueled: Boolean,
    val error: String? = null,
    val fuelAdded: Int? = null,
    val ts: Long = System.currentTimeMillis() / 1000
)

/**
 * Sent by a turtle after each connect and whenever its upgrades change. [itemsKnown] is false on CC: Tweaked versions
 * without turtle.getEquippedLeft/Right, where [left] and [right] only describe peripheral upgrades.
 */
@Serializable
data class EquipmentUpdate(
    val v: Int = Protocol.VERSION,
    val type: String = Protocol.MessageTypes.EQUIPMENT_UPDATE,
    val turtleId: Int,
    val left: EquippedUpgrade? = null,
    val right: EquippedUpgrade? = null,
    val itemsKnown: Boolean = false,
    val ts: Long = System.currentTimeMillis() / 1000
)

/**
 * Sent by a turtle after running a [CommandMessage]. It reports the position and inventory the command changed first,
 * so the dashboard already has them when it sees the result.
 */
@Serializable
data class CommandResultUpdate(
    val v: Int = Protocol.VERSION,
    val type: String = Protocol.MessageTypes.COMMAND_RESULT,
    val turtleId: Int,
    val commandId: Long,
    val command: String,
    val success: Boolean,
    val error: String? = null,
    val ts: Long = System.currentTimeMillis() / 1000
)

/**
 * Server → turtle: run [command] (one of [protocol.api.Commands]). The turtle replies with a [CommandResultUpdate]
 * carrying the same [commandId].
 */
@Serializable
data class CommandMessage(
    val v: Int = Protocol.VERSION,
    val type: String = Protocol.MessageTypes.COMMAND,
    val commandId: Long,
    val command: String
)

/** Server → turtle: select inventory slot [slot] (1..16). The turtle replies with an [InventoryUpdate]. */
@Serializable
data class SelectSlotMessage(
    val v: Int = Protocol.VERSION,
    val type: String = Protocol.MessageTypes.SELECT_SLOT,
    val slot: Int
)

/**
 * Server → turtle: burn [count] items from the selected slot as fuel (null burns the whole stack). The turtle replies
 * with a [RefuelResultUpdate] carrying the same [refuelId].
 */
@Serializable
data class RefuelMessage(
    val v: Int = Protocol.VERSION,
    val type: String = Protocol.MessageTypes.REFUEL,
    val refuelId: Long,
    val count: Int? = null
)

/** Server → turtle: adopt this position. Sent until a position update carrying its seedId acknowledges it. */
@Serializable
data class SeedMessage(
    val v: Int = Protocol.VERSION,
    val type: String = Protocol.MessageTypes.SEED,
    val seed: PositionSeed
)

/** Server → turtle: set your label to [label] (null clears it). Sent until a matching [LabelUpdate] or [Hello]. */
@Serializable
data class RenameMessage(
    val v: Int = Protocol.VERSION,
    val type: String = Protocol.MessageTypes.RENAME,
    val label: String? = null
)

/** Server → turtle: the last message couldn't be handled. The connection stays open. */
@Serializable
data class ErrorMessage(
    val v: Int = Protocol.VERSION,
    val type: String = Protocol.MessageTypes.ERROR,
    val error: String
)
