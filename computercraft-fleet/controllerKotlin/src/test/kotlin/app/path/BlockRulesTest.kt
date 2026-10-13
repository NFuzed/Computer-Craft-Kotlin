package app.path

import protocol.api.AIR
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlockRulesTest {

    @Test
    fun `turtles can move through air and liquids`() {
        for (name in listOf(AIR, "minecraft:cave_air", "minecraft:water", "minecraft:lava")) {
            assertTrue(BlockRules.isOpen(name), name)
        }
        assertFalse(BlockRules.isOpen("minecraft:stone"))
    }

    @Test
    fun `turtles, computers, storage and unbreakable blocks are protected`() {
        val protected = listOf(
            "computercraft:turtle_normal", "computercraft:turtle_advanced", "computercraft:computer_advanced",
            "computercraft:monitor_normal", "computercraft:wired_modem_full",
            "minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel", "minecraft:shulker_box",
            "minecraft:red_shulker_box", "minecraft:hopper", "minecraft:furnace",
            "minecraft:bedrock", "minecraft:spawner", "minecraft:end_portal_frame"
        )
        for (name in protected) {
            assertTrue(BlockRules.isProtected(name), name)
        }
        for (name in listOf("minecraft:stone", "minecraft:dirt", "minecraft:gravel", "minecraft:oak_log", AIR)) {
            assertFalse(BlockRules.isProtected(name), name)
        }
    }
}
