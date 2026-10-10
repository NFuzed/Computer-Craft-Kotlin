package protocol.messages

import kotlinx.serialization.Serializable
import protocol.Protocol
import protocol.api.InventorySlot
import protocol.api.PositionSeed

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
    val ts: Long = System.currentTimeMillis() / 1000
)

/** Server → turtle: select inventory slot [slot] (1..16). The turtle replies with an [InventoryUpdate]. */
@Serializable
data class SelectSlotMessage(
    val v: Int = Protocol.VERSION,
    val type: String = Protocol.MessageTypes.SELECT_SLOT,
    val slot: Int
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
