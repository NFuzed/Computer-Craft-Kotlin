package protocol.api

import kotlinx.serialization.Serializable

@Serializable
data class TurtleStatus(
    val turtleId: Int,
    val label: String? = null,
    val online: Boolean,
    val lastSeen: Long? = null,     // unix seconds, null if never seen
    val position: TurtlePosition? = null,
    val pendingSeed: PositionSeed? = null, // seed sent from the dashboard that the turtle hasn't applied yet
    val pendingLabel: PendingLabel? = null, // rename sent from the dashboard that the turtle hasn't applied yet
    val equipment: TurtleEquipment? = null, // null until the turtle reports it (older turtle code)
    val lastCommand: CommandResult? = null  // the turtle's latest command since the server started; not saved
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
