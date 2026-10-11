package app.service

import protocol.api.Capabilities
import protocol.api.EquippedUpgrade

// Item id suffixes of the vanilla tools a turtle can equip (any material)
private val DIGGING_TOOLS = listOf("_pickaxe", "_shovel", "_axe", "_hoe")
private const val SWORD = "_sword"

// Peripheral types whose capability has a different name; any other type is used as is
private val PERIPHERAL_CAPABILITIES = mapOf("workbench" to Capabilities.CRAFT)

/** What a turtle with these upgrades can do, sorted and without duplicates. See [Capabilities]. */
fun capabilitiesOf(left: EquippedUpgrade?, right: EquippedUpgrade?): List<String> =
    listOfNotNull(left, right).flatMap(::capabilitiesOf).distinct().sorted()

private fun capabilitiesOf(upgrade: EquippedUpgrade): List<String> {
    val item = upgrade.item
    return when {
        item != null && DIGGING_TOOLS.any(item::endsWith) -> listOf(Capabilities.DIG, Capabilities.ATTACK)
        item != null && item.endsWith(SWORD) -> listOf(Capabilities.ATTACK)
        // Tools aren't peripherals, so the cases above ignore peripheralTypes: on a tool's side they come from the block
        // next to the turtle. Peripheral upgrades are told apart by type rather than item, so modded ones work too

        else -> upgrade.peripheralTypes.map { PERIPHERAL_CAPABILITIES[it] ?: it }
    }
}
