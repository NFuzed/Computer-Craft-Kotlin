package app.store

import app.support.withDatabase
import protocol.api.AIR
import protocol.api.KnownBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private val OVERWORLD = MapId("w", "overworld")
private val NETHER = MapId("w", "nether")

private fun block(x: Int, y: Int, z: Int, name: String = "minecraft:stone", turtleId: Int = 1, ts: Long = 100) =
    KnownBlock(x = x, y = y, z = z, name = name, turtleId = turtleId, ts = ts)

private fun WorldMapStore.everything(id: MapId, includeAir: Boolean = true) =
    region(id, BlockPos(-100, -64, -100), BlockPos(100, 320, 100), includeAir)

class WorldMapStoreTest {

    @Test
    fun `a recorded block can be read back`() = withDatabase { db ->
        val store = WorldMapStore(db)
        store.record(OVERWORLD, listOf(block(1, 64, 2)))

        assertEquals(block(1, 64, 2), store.get(OVERWORLD, BlockPos(1, 64, 2)))
        assertNull(store.get(OVERWORLD, BlockPos(1, 65, 2)))
    }

    @Test
    fun `a new report replaces what was known about the cell`() = withDatabase { db ->
        val store = WorldMapStore(db)
        store.record(OVERWORLD, listOf(block(0, 64, 0, "minecraft:stone", ts = 100)))
        store.record(OVERWORLD, listOf(block(0, 64, 0, AIR, turtleId = 2, ts = 200)))

        assertEquals(block(0, 64, 0, AIR, turtleId = 2, ts = 200), store.get(OVERWORLD, BlockPos(0, 64, 0)))
    }

    @Test
    fun `the last report of a cell in one message wins`() = withDatabase { db ->
        val store = WorldMapStore(db)
        store.record(OVERWORLD, listOf(block(0, 64, 0, "minecraft:stone"), block(0, 64, 0, AIR)))

        assertEquals(AIR, store.get(OVERWORLD, BlockPos(0, 64, 0))?.name)
    }

    @Test
    fun `maps for different dimensions are separate`() = withDatabase { db ->
        val store = WorldMapStore(db)
        store.record(OVERWORLD, listOf(block(0, 64, 0, "minecraft:stone")))
        store.record(NETHER, listOf(block(0, 64, 0, "minecraft:netherrack")))

        assertEquals("minecraft:stone", store.get(OVERWORLD, BlockPos(0, 64, 0))?.name)
        assertEquals("minecraft:netherrack", store.get(NETHER, BlockPos(0, 64, 0))?.name)
        assertNull(store.get(MapId(null, null), BlockPos(0, 64, 0)))
    }

    @Test
    fun `a region includes its bounds and is sorted by y, then z, then x`() = withDatabase { db ->
        val store = WorldMapStore(db)
        val inside = listOf(block(2, 60, 0), block(0, 60, 1), block(1, 60, 1), block(0, 61, 0), block(2, 61, 2))
        val outside = listOf(block(3, 60, 0), block(0, 59, 0), block(0, 62, 0), block(0, 60, -1), block(0, 60, 3))
        store.record(OVERWORLD, (inside + outside).shuffled())

        val region = store.region(OVERWORLD, BlockPos(0, 60, 0), BlockPos(2, 61, 2))

        assertEquals(
            listOf(block(2, 60, 0), block(0, 60, 1), block(1, 60, 1), block(0, 61, 0), block(2, 61, 2)),
            region
        )
    }

    @Test
    fun `a region spanning chunks, including negative ones, finds every block`() = withDatabase { db ->
        val store = WorldMapStore(db)
        // chunk boundaries: -17|-16 and -1|0 and 15|16 (x >> 4 rounds down for negatives)
        val xs = listOf(-17, -16, -1, 0, 15, 16)
        // and the same along z; x and -x - 1 are never equal, so every block is in its own cell
        store.record(OVERWORLD, xs.flatMap { x -> listOf(block(x, 64, x), block(x, 64, -x - 1)) })

        val region = store.region(OVERWORLD, BlockPos(-17, 64, -17), BlockPos(16, 64, 17))

        assertEquals(xs.size * 2, region.size)
    }

    @Test
    fun `a region can leave out known air`() = withDatabase { db ->
        val store = WorldMapStore(db)
        store.record(OVERWORLD, listOf(block(0, 64, 0, AIR), block(1, 64, 0, "minecraft:stone")))

        assertEquals(2, store.everything(OVERWORLD).size)
        assertEquals(listOf(block(1, 64, 0, "minecraft:stone")), store.everything(OVERWORLD, includeAir = false))
    }

    @Test
    fun `moving a turtle's blocks takes only that turtle's, and newer reports win`() = withDatabase { db ->
        val store = WorldMapStore(db)
        store.record(
            OVERWORLD,
            listOf(block(0, 64, 0, turtleId = 1, ts = 200), block(1, 64, 0, turtleId = 1, ts = 200))
        )
        store.record(OVERWORLD, listOf(block(5, 64, 5, turtleId = 2)))
        // the nether already knows (0, 64, 0) from an older report and (1, 64, 0) from a newer one
        store.record(NETHER, listOf(block(0, 64, 0, AIR, turtleId = 3, ts = 100)))
        store.record(NETHER, listOf(block(1, 64, 0, AIR, turtleId = 3, ts = 300)))

        val moved = store.moveTurtleBlocks(turtleId = 1, from = OVERWORLD, to = NETHER)

        assertEquals(2, moved)
        assertEquals(listOf(block(5, 64, 5, turtleId = 2)), store.everything(OVERWORLD))
        assertEquals(
            listOf(block(0, 64, 0, turtleId = 1, ts = 200), block(1, 64, 0, AIR, turtleId = 3, ts = 300)),
            store.everything(NETHER)
        )
    }
}
