package protocol.api

import kotlinx.serialization.Serializable

/**
 * Values of [CommandRequest.command]: one turtle API call each. Moves and turns go through the turtle's dead reckoning,
 * so its position stays right. The dig and attack commands need the matching [Capabilities].
 */
object Commands {
    const val FORWARD = "forward"
    const val BACK = "back"
    const val UP = "up"
    const val DOWN = "down"
    const val TURN_LEFT = "turnLeft"
    const val TURN_RIGHT = "turnRight"

    const val DIG = "dig"
    const val DIG_UP = "digUp"
    const val DIG_DOWN = "digDown"
    const val PLACE = "place"
    const val PLACE_UP = "placeUp"
    const val PLACE_DOWN = "placeDown"
    const val DROP = "drop"             // the whole selected stack
    const val DROP_UP = "dropUp"
    const val DROP_DOWN = "dropDown"
    const val SUCK = "suck"
    const val SUCK_UP = "suckUp"
    const val SUCK_DOWN = "suckDown"
    const val ATTACK = "attack"
    const val ATTACK_UP = "attackUp"
    const val ATTACK_DOWN = "attackDown"

    val ALL = setOf(
        FORWARD, BACK, UP, DOWN, TURN_LEFT, TURN_RIGHT,
        DIG, DIG_UP, DIG_DOWN, PLACE, PLACE_UP, PLACE_DOWN,
        DROP, DROP_UP, DROP_DOWN, SUCK, SUCK_UP, SUCK_DOWN,
        ATTACK, ATTACK_UP, ATTACK_DOWN
    )
}

/** Sent by the dashboard to make a turtle run one of [Commands]. */
@Serializable
data class CommandRequest(
    val command: String
)

/** Returned with 202 once a command has been sent to the turtle. Its [commandId] matches the turtle's [CommandResult]. */
@Serializable
data class TurtleCommand(
    val commandId: Long,
    val command: String,
    val ts: Long
)

/** What happened when the turtle ran a command. */
@Serializable
data class CommandResult(
    val commandId: Long,
    val command: String,
    val success: Boolean,
    val error: String? = null,      // the turtle API's reason when success is false, e.g. "Movement obstructed"
    val ts: Long
)
