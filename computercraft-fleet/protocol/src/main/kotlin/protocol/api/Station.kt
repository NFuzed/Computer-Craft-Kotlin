package protocol.api

import kotlinx.serialization.Serializable

/** Values of [Station.type]: what turtles go to the station for. */
object StationTypes {
    const val FUEL = "fuel"         // take fuel from the chest and burn it
    const val DROP = "drop"         // put items into the chest
    const val PICKUP = "pickup"     // take items someone has put in the chest for this turtle

    val ALL = setOf(FUEL, DROP, PICKUP)
}

/** Values of [Station.side]: where the chest is, seen from the station's standing cell. */
object StationSides {
    const val UP = "up"
    const val DOWN = "down"
    const val FRONT = "front"       // along the station's heading, which is then required

    val ALL = setOf(UP, DOWN, FRONT)
}

/** Station names are shown on the dashboard; same limit as turtle labels. */
const val MAX_STATION_NAME_LENGTH = 32

/**
 * Sent by the dashboard to create or replace a station. [x], [y], [z] is the cell the turtle stands in, not the chest.
 * Stations are shared by the whole fleet; a turtle uses the nearest one of the type it needs.
 */
@Serializable
data class StationRequest(
    val name: String,
    val type: String,               // one of StationTypes
    val x: Int,
    val y: Int,
    val z: Int,
    val heading: String? = null,    // "N","E","S","W": which way the turtle faces there. Required when side is front
    val side: String = StationSides.UP,
    val world: String? = null,
    val dimension: String? = null
)

/** A place turtles go to refuel, drop items off or pick them up. */
@Serializable
data class Station(
    val stationId: Long,
    val name: String,
    val type: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val heading: String? = null,
    val side: String,
    val world: String? = null,
    val dimension: String? = null,
    val ts: Long                    // when it was created or last replaced, unix seconds
)
