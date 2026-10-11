package protocol.api

import kotlinx.serialization.Serializable

/**
 * Values in [TurtleEquipment.capabilities]: what a turtle's upgrades let it do, beyond what every turtle can (move,
 * place, drop, suck, refuel...). A peripheral the server has no name for shows up as its peripheral type instead.
 */
object Capabilities {
    const val DIG = "dig"           // a pickaxe, shovel, axe or hoe
    const val ATTACK = "attack"     // any tool
    const val CRAFT = "craft"       // crafting table upgrade (peripheral type "workbench")
    const val MODEM = "modem"       // wireless or ender modem
    const val SPEAKER = "speaker"
}

/** What a turtle has equipped on one side. */
@Serializable
data class EquippedUpgrade(
    val item: String? = null,                           // item id, e.g. "minecraft:diamond_pickaxe"; null if unknown
    val peripheralTypes: List<String> = emptyList()     // peripheral.getType of that side, e.g. ["modem"]; empty for tools
)

/**
 * The upgrades a turtle last reported, and the [capabilities] the server worked out from them. A null side has nothing
 * equipped, but when [itemsKnown] is false the turtle couldn't read item names (older CC: Tweaked), so a null side may
 * still hold a tool.
 */
@Serializable
data class TurtleEquipment(
    val left: EquippedUpgrade? = null,
    val right: EquippedUpgrade? = null,
    val itemsKnown: Boolean,
    val capabilities: List<String>, // see [Capabilities]
    val ts: Long
)
