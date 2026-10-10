package protocol.messages

import kotlinx.serialization.Serializable
import protocol.Protocol

@Serializable
data class PositionUpdate(
    val v: Int = Protocol.VERSION,
    val type: String = Protocol.MessageTypes.POSITION_UPDATE,
    val turtleId: Int,
    val world: String? = null,      // optional: server/world name
    val x: Int,
    val y: Int,
    val z: Int,
    val heading: String? = null,    // "N","E","S","W"
    val dimension: String? = null,  // optional
    val seedId: Long? = null,       // the seed this position is based on; acknowledges it
    val ts: Long = System.currentTimeMillis() / 1000
)
