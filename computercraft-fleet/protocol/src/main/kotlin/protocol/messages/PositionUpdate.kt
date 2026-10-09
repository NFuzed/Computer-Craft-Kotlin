package protocol.messages

import kotlinx.serialization.Serializable
import protocol.Protocol

@Serializable
data class PositionUpdate(
    val v: Int = Protocol.VERSION,
    val type: String = "position_update",
    val turtleId: Int,
    val world: String? = null,      // optional: server/world name
    val x: Int,
    val y: Int,
    val z: Int,
    val heading: String? = null,    // "N","E","S","W"
    val dimension: String? = null,  // optional
    val ts: Long = System.currentTimeMillis() / 1000
)
