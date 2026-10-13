package app.path

import protocol.api.AIR

/** Which blocks a turtle can move through, and which it must never dig or enter. */
object BlockRules {
    /** Turtles move through these without digging. */
    private val OPEN = setOf(
        AIR, "minecraft:cave_air", "minecraft:void_air",
        "minecraft:water", "minecraft:lava", "minecraft:bubble_column"
    )

    /** Never dug or entered: storage (digging spills it), unbreakable blocks, and portals (entering one teleports). */
    private val PROTECTED = setOf(
        "minecraft:chest", "minecraft:trapped_chest", "minecraft:ender_chest", "minecraft:barrel",
        "minecraft:hopper", "minecraft:dropper", "minecraft:dispenser",
        "minecraft:furnace", "minecraft:blast_furnace", "minecraft:smoker", "minecraft:brewing_stand",
        "minecraft:bedrock", "minecraft:barrier", "minecraft:spawner", "minecraft:reinforced_deepslate",
        "minecraft:end_portal_frame", "minecraft:end_portal", "minecraft:nether_portal", "minecraft:end_gateway"
    )

    fun isOpen(name: String): Boolean = name in OPEN

    fun isProtected(name: String): Boolean =
        name in PROTECTED ||
                name.startsWith("computercraft:") ||  // other turtles, computers, monitors, modems, disk drives
                name.endsWith("shulker_box")          // every colour
}
