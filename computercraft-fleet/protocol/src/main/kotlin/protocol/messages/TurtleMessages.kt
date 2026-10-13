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

/** One block a turtle inspected, at world coordinates worked out from its dead-reckoned position and heading. */
@Serializable
data class ObservedBlock(
    val x: Int,
    val y: Int,
    val z: Int,
    val name: String                // block id, or protocol.api.AIR if nothing was there
)

/**
 * Sent by a turtle after it connects, is seeded or runs a command: the blocks in front of, above and below it, from
 * turtle.inspect, inspectUp and inspectDown. The server adds them to its world map, replacing what it knew about
 * those cells.
 */
@Serializable
data class BlockObservations(
    val v: Int = Protocol.VERSION,
    val type: String = Protocol.MessageTypes.BLOCK_OBSERVATIONS,
    val turtleId: Int,
    val world: String? = null,
    val dimension: String? = null,
    val blocks: List<ObservedBlock>,
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

/** Values of [PathStepMessage.action]: the turtle API call for each step. Moves and turns go through nav.lua. */
object PathActions {
    const val FORWARD = "forward"
    const val BACK = "back"
    const val UP = "up"
    const val DOWN = "down"
    const val TURN_LEFT = "turnLeft"
    const val TURN_RIGHT = "turnRight"
}

/** One step of a [PathMessage]. With [dig], the cell is expected to be solid: the turtle digs it, then moves. */
@Serializable
data class PathStepMessage(
    val action: String,             // one of PathActions
    val dig: Boolean = false
)

/** Where a path was planned from. */
@Serializable
data class PathStart(
    val x: Int,
    val y: Int,
    val z: Int,
    val heading: String             // "N","E","S","W"
)

/**
 * Server → turtle: follow these steps. After each move or turn the turtle reports its position and the blocks around
 * it; when it finishes or stops it sends a [PathResultUpdate] with the same [pathId]. A new path replaces one that's
 * still running.
 *
 * The steps only lead to the goal from [start], so a turtle that isn't there (it moved after the server planned)
 * doesn't move, and replies blocked; the server then plans again from where it is.
 */
@Serializable
data class PathMessage(
    val v: Int = Protocol.VERSION,
    val type: String = Protocol.MessageTypes.PATH,
    val pathId: Long,
    val start: PathStart,
    val steps: List<PathStepMessage>
)

/** Server → turtle: stop following path [pathId] after the current step. The turtle replies with a cancelled result. */
@Serializable
data class PathCancelMessage(
    val v: Int = Protocol.VERSION,
    val type: String = Protocol.MessageTypes.PATH_CANCEL,
    val pathId: Long
)

/** Values of [PathResultUpdate.status]. */
object PathResults {
    const val DONE = "done"             // every step ran
    const val BLOCKED = "blocked"       // a move failed: something is in the way, or it couldn't be dug
    const val FAILED = "failed"         // anything else, e.g. out of fuel; see error
    const val CANCELLED = "cancelled"
}

/**
 * Sent by a turtle when it finishes or stops following a [PathMessage], after its position and the blocks around it,
 * so the server knows what stopped it before it replans. [stepsDone] counts the steps that ran.
 */
@Serializable
data class PathResultUpdate(
    val v: Int = Protocol.VERSION,
    val type: String = Protocol.MessageTypes.PATH_RESULT,
    val turtleId: Int,
    val pathId: Long,
    val status: String,             // one of PathResults
    val stepsDone: Int,
    val error: String? = null,      // the turtle API's reason, e.g. "Movement obstructed"
    val ts: Long = System.currentTimeMillis() / 1000
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

/**
 * Server → turtle, every [Protocol.HEARTBEAT_INTERVAL_SECONDS]. The turtle ignores it, but reconnects if it hears
 * nothing from the server for a while.
 */
@Serializable
data class HeartbeatMessage(
    val v: Int = Protocol.VERSION,
    val type: String = Protocol.MessageTypes.HEARTBEAT
)

/** Server → turtle: the last message couldn't be handled. The connection stays open. */
@Serializable
data class ErrorMessage(
    val v: Int = Protocol.VERSION,
    val type: String = Protocol.MessageTypes.ERROR,
    val error: String
)
