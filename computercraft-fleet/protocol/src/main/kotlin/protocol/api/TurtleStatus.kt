package protocol.api

import kotlinx.serialization.Serializable

@Serializable
data class TurtleStatus(
    val turtleId: Int,
    val label: String? = null,
    val online: Boolean,
    val lastSeen: Long? = null,     // unix seconds, null if never seen
    val position: TurtlePosition? = null
)

@Serializable
data class TurtlePosition(
    val x: Int,
    val y: Int,
    val z: Int,
    val heading: String? = null,
    val world: String? = null,
    val dimension: String? = null,
    val ts: Long
)
