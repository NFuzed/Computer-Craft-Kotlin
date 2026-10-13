package app.path

import app.store.BlockPos
import protocol.api.AIR
import protocol.api.KnownBlock
import kotlin.test.*

private const val STONE = "minecraft:stone"
private const val SURFACE = 62

private fun known(x: Int, y: Int, z: Int, name: String) = KnownBlock(x, y, z, name, ts = 0)

/** A world knowing only [blocks], guessing the surface at y [SURFACE]. */
private fun world(vararg blocks: KnownBlock) = KnownWorld(blocks.toList(), surfaceGuess = SURFACE)

class KnownWorldTest {

    @Test
    fun `known blocks are what was seen`() {
        val world = world(known(0, 64, 0, STONE), known(0, 65, 0, AIR))

        assertEquals(STONE, world.blockAt(BlockPos(0, 64, 0)))
        assertEquals(AIR, world.blockAt(BlockPos(0, 65, 0)))
        assertNull(world.blockAt(BlockPos(0, 66, 0)))
    }

    @Test
    fun `with nothing known nearby, below the surface guess is solid and above it is air`() {
        val world = world()

        assertTrue(world.probablySolid(BlockPos(0, SURFACE - 1, 0)))
        assertFalse(world.probablySolid(BlockPos(0, SURFACE, 0)))
        assertFalse(world.probablySolid(BlockPos(0, 100, 0)))
    }

    @Test
    fun `under a known solid block is solid, even above the surface guess`() {
        // e.g. inside a mountain, or under a turtle's tunnel ceiling
        val world = world(known(0, 100, 0, STONE))

        assertTrue(world.probablySolid(BlockPos(0, 90, 0)))
    }

    @Test
    fun `between known air above and known solid below is solid`() {
        // the ground under a turtle that flew over without looking down
        val world = world(known(0, 70, 0, AIR), known(0, 64, 0, STONE))

        assertTrue(world.probablySolid(BlockPos(0, 65, 0)))
    }

    @Test
    fun `between known air above and below is air`() {
        val world = world(known(0, 80, 0, AIR), known(0, 70, 0, AIR))

        assertFalse(world.probablySolid(BlockPos(0, 75, 0)))
    }

    @Test
    fun `above the highest known block, air means open sky`() {
        val world = world(known(0, 64, 0, STONE), known(0, 65, 0, AIR))

        assertFalse(world.probablySolid(BlockPos(0, 90, 0)))
        // even below the surface guess: above known air in a big cave is open too
        val cave = world(known(0, 30, 0, AIR))
        assertFalse(cave.probablySolid(BlockPos(0, 35, 0)))
    }

    @Test
    fun `above a highest known solid block, the surface guess decides`() {
        // the ceiling of a tunnel at y 21: rock above it underground, but air above known ground on the surface
        val world = world(known(0, 21, 0, STONE), known(5, 64, 0, STONE))

        assertTrue(world.probablySolid(BlockPos(0, 25, 0)))
        assertFalse(world.probablySolid(BlockPos(5, 65, 0)))
    }

    @Test
    fun `under known air with nothing known below, the surface guess decides`() {
        val world = world(known(0, 30, 0, AIR), known(5, 80, 0, AIR))

        assertTrue(world.probablySolid(BlockPos(0, 20, 0)))
        assertFalse(world.probablySolid(BlockPos(5, 70, 0)))
    }

    @Test
    fun `a column nobody has seen borrows from the nearest column that has been seen`() {
        // a tunnel at y 20 with rock above it: the columns beside it are probably rock at that height too
        val world = world(known(0, 21, 0, STONE), known(0, 19, 0, STONE))

        assertTrue(world.probablySolid(BlockPos(3, 20, 0)), "a few blocks away")
        assertTrue(world.probablySolid(BlockPos(2, 30, 2)), "above the borrowed ceiling, below the surface guess")
    }

    @Test
    fun `a column too far from any seen column falls back to the surface guess`() {
        val world = world(known(0, 100, 0, STONE))

        assertFalse(world.probablySolid(BlockPos(50, 90, 0)))
    }

    @Test
    fun `when exploring, every unseen cell is guessed open`() {
        val world = KnownWorld(
            listOf(known(0, 100, 0, STONE), known(0, 64, 0, STONE), known(0, 70, 0, AIR)),
            surfaceGuess = SURFACE, assumeUnseenOpen = true
        )

        assertFalse(world.probablySolid(BlockPos(0, 90, 0)), "under known stone")
        assertFalse(world.probablySolid(BlockPos(0, 65, 0)), "between air and ground")
        assertFalse(world.probablySolid(BlockPos(50, 10, 0)), "deep underground, nothing known")
        assertEquals(STONE, world.blockAt(BlockPos(0, 64, 0)), "what was seen still counts")
    }

    @Test
    fun `the nearest seen column wins`() {
        // open sky seen 2 blocks east, rock seen 5 blocks west
        val world = world(known(2, 70, 0, AIR), known(-5, 80, 0, STONE))

        assertFalse(world.probablySolid(BlockPos(0, 75, 0)))
    }
}
