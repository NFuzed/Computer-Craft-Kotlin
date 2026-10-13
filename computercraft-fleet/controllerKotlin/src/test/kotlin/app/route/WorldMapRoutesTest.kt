package app.route

import app.store.BlockPos
import app.support.fleetTest
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import protocol.api.*
import protocol.messages.BlockObservations
import protocol.messages.ObservedBlock
import protocol.messages.PositionUpdate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val BOX = "minX=-5&minY=60&minZ=-5&maxX=5&maxY=70&maxZ=5"

class WorldMapRoutesTest {

    @Test
    fun `blocks a turtle observes show up in the map`() = fleetTest {
        turtle(7) {
            send(
                BlockObservations(
                    turtleId = 7, world = "w", dimension = "overworld", ts = 1000,
                    blocks = listOf(
                        ObservedBlock(1, 64, 0, "minecraft:stone"),
                        ObservedBlock(0, 65, 0, AIR)
                    )
                )
            )
            sync()
        }

        val region = client.get("/api/map/blocks?$BOX&world=w&dimension=overworld").body<MapRegion>()

        assertEquals(
            MapRegion(
                world = "w", dimension = "overworld", blocks = listOf(
                    KnownBlock(1, 64, 0, "minecraft:stone", turtleId = 7, ts = 1000),
                    KnownBlock(0, 65, 0, AIR, turtleId = 7, ts = 1000)
                )
            ),
            region
        )
    }

    @Test
    fun `another turtle that was seen is stored as the air it stands in, since it moves`() = fleetTest {
        turtle(7) {
            send(
                BlockObservations(
                    turtleId = 7, ts = 1000,
                    blocks = listOf(
                        ObservedBlock(1, 64, 0, "computercraft:turtle_normal"),
                        ObservedBlock(2, 64, 0, "computercraft:turtle_advanced")
                    )
                )
            )
            sync()
        }

        val region = client.get("/api/map/blocks?$BOX").body<MapRegion>()

        assertEquals(listOf(AIR, AIR), region.blocks.map { it.name })
    }

    @Test
    fun `the cell a turtle reports standing in is known air`() = fleetTest {
        turtle(7) {
            send(
                PositionUpdate(
                    turtleId = 7,
                    world = "w",
                    dimension = "overworld",
                    x = 1,
                    y = 64,
                    z = 0,
                    heading = "N"
                )
            )
            sync()
        }

        val region = client.get("/api/map/blocks?$BOX&world=w&dimension=overworld").body<MapRegion>()

        assertEquals(listOf(BlockPos(1, 64, 0) to AIR), region.blocks.map { BlockPos(it.x, it.y, it.z) to it.name })
    }

    @Test
    fun `excludeAir leaves out known air`() = fleetTest {
        turtle(7) {
            send(
                BlockObservations(
                    turtleId = 7, ts = 1000,
                    blocks = listOf(ObservedBlock(1, 64, 0, "minecraft:stone"), ObservedBlock(0, 65, 0, AIR))
                )
            )
            sync()
        }

        val region = client.get("/api/map/blocks?$BOX&excludeAir=true").body<MapRegion>()

        assertEquals(listOf("minecraft:stone"), region.blocks.map { it.name })
    }

    @Test
    fun `a map query needs every bound`() = fleetTest {
        val response = client.get("/api/map/blocks?minX=0&minY=0&minZ=0&maxX=1&maxY=1")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("maxZ is required and must be an integer", response.body<ApiError>().error)
    }

    @Test
    fun `a map query rejects a min above its max`() = fleetTest {
        val response = client.get("/api/map/blocks?minX=5&minY=0&minZ=0&maxX=0&maxY=0&maxZ=0")

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `a map query rejects a box that is too big`() = fleetTest {
        val ok = client.get("/api/map/blocks?minX=0&minY=0&minZ=0&maxX=${MAX_MAP_QUERY_SPAN - 1}&maxY=0&maxZ=0")
        val tooBig = client.get("/api/map/blocks?minX=0&minY=0&minZ=0&maxX=$MAX_MAP_QUERY_SPAN&maxY=0&maxZ=0")

        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals(HttpStatusCode.BadRequest, tooBig.status)
        assertTrue("at most $MAX_MAP_QUERY_SPAN" in tooBig.body<ApiError>().error)
    }
}
