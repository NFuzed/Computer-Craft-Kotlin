package app.route

import app.path.Heading.*
import app.store.BlockPos
import app.support.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import protocol.api.*
import kotlin.test.*

/**
 * Sending turtles somewhere, end to end: the real server plans and replans, and [SimTurtle]s follow the paths in a
 * [SimWorld]. The server starts out knowing nothing about the world; it learns it from what the turtles report.
 * Worlds are drawn at y 70, above the server's surface guess (62), and everything outside a drawing is stone.
 */
class GotoTest {

    private suspend fun FleetTest.started(turtleId: Int, x: Int, y: Int, z: Int, heading: String? = null): Trip {
        val response = goto(turtleId, x, y, z, heading)
        assertEquals(HttpStatusCode.Accepted, response.status, response.bodyAsText())
        return response.body()
    }

    private suspend fun HttpResponse.error(): String = body<ApiError>().error

    /** Waits until the turtle's trip is [status], and returns it. */
    private suspend fun FleetTest.awaitStatus(turtleId: Int, status: String, timeoutMs: Long = 10_000): Trip {
        var last: Trip? = null
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                last = trip(turtleId)
                if (last!!.status == status) return@withTimeoutOrNull
                delay(10)
            }
        } ?: fail("The trip wasn't $status within $timeoutMs ms: $last")
        return last!!
    }

    /**
     * Marks the blocks at [x] from [minZ] to [maxZ] as a zone; open in Y unless [minY] or [maxY] is given.
     */
    private suspend fun FleetTest.zone(
        x: Int, minZ: Int, maxZ: Int = minZ, type: String = ZoneTypes.NO_DIG, minY: Int? = null, maxY: Int? = null,
        dimension: String? = null
    ) {
        val response = client.post("/api/zones") {
            contentType(ContentType.Application.Json)
            setBody(
                ZoneRequest(
                    name = "Zone", type = type, minX = x, maxX = x, minZ = minZ, maxZ = maxZ, minY = minY, maxY = maxY,
                    dimension = dimension
                )
            )
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
    }

    // --- arriving ---

    @Test
    fun `a turtle goes down a corridor and arrives`() = fleetTest {
        val world = SimWorld.parse("y=70\n.....")
        val turtle = simTurtle(7, world, at(0, 70, 0, E))

        val trip = started(7, 4, 70, 0)
        assertEquals(TripStatuses.MOVING, trip.status)

        val done = awaitTrip(7)
        assertEquals(TripStatuses.ARRIVED, done.status, done.error)
        assertEquals(BlockPos(4, 70, 0), turtle.pose.pos)
    }

    @Test
    fun `it ends facing the requested heading`() = fleetTest {
        val world = SimWorld.parse("y=70\n.....")
        val turtle = simTurtle(7, world, at(0, 70, 0, E))

        started(7, 4, 70, 0, heading = "N")

        assertEquals(TripStatuses.ARRIVED, awaitTrip(7).status)
        assertEquals(at(4, 70, 0, N), turtle.pose)
    }

    @Test
    fun `it crosses unexplored open ground in a straight line, instead of going up and over`() = fleetTest {
        // below the surface guess, so a pessimistic guess would call the unseen room rock and send the turtle up to
        // where it guesses there's air
        val world = SimWorld.parse("y=58\n" + ".".repeat(40))
        val turtle = simTurtle(7, world, at(0, 58, 0, E), canDig = true)

        started(7, 39, 58, 0)

        val done = awaitTrip(7)
        assertEquals(TripStatuses.ARRIVED, done.status, done.error)
        assertEquals(BlockPos(39, 58, 0), turtle.pose.pos)
        val actions = turtle.pathsReceived.flatMap { path -> path.steps.map { it.action } }
        assertEquals(0, done.replans)
        assertEquals(List(39) { "forward" }, actions)
        assertEquals("minecraft:stone", world.terrainAt(BlockPos(5, 59, 0)), "it dug up out of the room")
    }

    @Test
    fun `after being blocked a few times it stops exploring and digs through`() = fleetTest {
        // a long tunnel blocked by stone, deep in rock: exploring would probe the unseen rock all round the plug
        val world = SimWorld.parse("y=30\n...#....")
        val turtle = simTurtle(7, world, at(0, 30, 0, E), canDig = true)

        started(7, 7, 30, 0)

        val done = awaitTrip(7)
        assertEquals(TripStatuses.ARRIVED, done.status, done.error)
        assertEquals(BlockPos(7, 30, 0), turtle.pose.pos)
        assertTrue(done.replans <= 4, "probed for too long: ${done.replans} new paths")
    }

    @Test
    fun `a wall the server didn't know about is found, and the turtle goes round it`() = fleetTest {
        val world = SimWorld.parse(
            """
            y=70
            ..#..
            .....
            """
        )
        val turtle = simTurtle(7, world, at(0, 70, 0, E), canDig = false)

        started(7, 4, 70, 0)

        val done = awaitTrip(7)
        assertEquals(TripStatuses.ARRIVED, done.status, done.error)
        assertEquals(BlockPos(4, 70, 0), turtle.pose.pos)
        assertTrue(done.replans >= 1, "it should have had to replan")
        assertEquals("minecraft:stone", world.terrainAt(BlockPos(2, 70, 0)), "it shouldn't have dug")
    }

    @Test
    fun `with a pickaxe and no way round, it digs through`() = fleetTest {
        val world = SimWorld.parse("y=70\n..#..")
        val turtle = simTurtle(7, world, at(0, 70, 0, E), canDig = true)

        started(7, 4, 70, 0)

        val done = awaitTrip(7)
        assertEquals(TripStatuses.ARRIVED, done.status, done.error)
        assertEquals(BlockPos(4, 70, 0), turtle.pose.pos)
        assertEquals(AIR, world.terrainAt(BlockPos(2, 70, 0)))
    }

    @Test
    fun `without a pickaxe and no way round, the trip fails`() = fleetTest {
        val world = SimWorld.parse("y=70\n..#..")
        simTurtle(7, world, at(0, 70, 0, E), canDig = false)

        started(7, 4, 70, 0)

        val done = awaitTrip(7)
        assertEquals(TripStatuses.FAILED, done.status)
        assertTrue("No path" in done.error.orEmpty(), done.error)
    }

    // --- no-dig zones ---

    @Test
    fun `with a pickaxe, it won't dig through a no-dig zone, even with no way round`() = fleetTest {
        val world = SimWorld.parse("y=70\n..#..")
        simTurtle(7, world, at(0, 70, 0, E), canDig = true)
        zone(2, minZ = -100, maxZ = 100) // the whole wall, top to bottom

        started(7, 4, 70, 0)

        val done = awaitTrip(7)
        assertEquals(TripStatuses.FAILED, done.status)
        assertNotEquals(AIR, world.terrainAt(BlockPos(2, 70, 0)), "the block in the zone was dug")
    }

    @Test
    fun `a no-dig zone only counts in its Y range, its dimension, and as no-dig`() = fleetTest {
        val world = SimWorld.parse("y=70\n..#..")
        val turtle = simTurtle(7, world, at(0, 70, 0, E), canDig = true)
        zone(2, 0, maxY = 60)
        zone(2, 0, minY = 80)
        zone(2, 0, dimension = "the_nether")
        zone(2, 0, type = ZoneTypes.QUARRY)

        started(7, 4, 70, 0)

        val done = awaitTrip(7)
        assertEquals(TripStatuses.ARRIVED, done.status, done.error)
        assertEquals(BlockPos(4, 70, 0), turtle.pose.pos)
        assertEquals(AIR, world.terrainAt(BlockPos(2, 70, 0)), "none of the zones covers the block, so it's dug")
    }

    @Test
    fun `it goes round another turtle instead of bumping into it`() = fleetTest {
        val world = SimWorld.parse(
            """
            y=70
            .....
            .....
            """
        )
        simTurtle(8, world, at(2, 70, 0, E))
        val turtle = simTurtle(7, world, at(0, 70, 0, E))

        started(7, 4, 70, 0)

        val done = awaitTrip(7)
        assertEquals(TripStatuses.ARRIVED, done.status, done.error)
        assertEquals(BlockPos(4, 70, 0), turtle.pose.pos)
        // it may bump into stone it couldn't know about, but never into a turtle the server knew was there
        assertFalse(TURTLE_BLOCK in turtle.obstructions, "obstructed by: ${turtle.obstructions}")
    }

    // --- making way ---

    /**
     * A corridor along z 0, with a pocket at x 2 z 1 that a turtle can pull over into. The server doesn't know it's a
     * corridor at first, so a trip tries the unseen cells round a turtle in the way before it asks it to move.
     */
    private val corridorWithPocket = """
        y=70
        .......
        ##.####
        """

    @Test
    fun `an idle turtle in the way pulls over to let it past`() = fleetTest {
        val world = SimWorld.parse(corridorWithPocket)
        val other = simTurtle(8, world, at(3, 70, 0, E))
        val turtle = simTurtle(7, world, at(0, 70, 0, E))

        started(7, 6, 70, 0)

        val done = awaitTrip(7)
        assertEquals(TripStatuses.ARRIVED, done.status, done.error)
        assertEquals(BlockPos(6, 70, 0), turtle.pose.pos)
        // into the pocket, or back past it once this one has used the pocket itself
        assertNotEquals(BlockPos(3, 70, 0), other.pose.pos, "it should have moved out of the way")
        assertFalse(TURTLE_BLOCK in turtle.obstructions, "it should have waited for the way to clear")
        val moved = trip(8)
        assertEquals(TripStatuses.ARRIVED, moved.status, moved.error)
        assertEquals(7, moved.makingWayFor)
    }

    @Test
    fun `with nowhere to pull over, the turtle in the way moves on past the goal`() = fleetTest {
        val world = SimWorld.parse("y=70\n.......")
        val other = simTurtle(8, world, at(2, 70, 0, W)) // facing the turtle it makes way for
        val turtle = simTurtle(7, world, at(0, 70, 0, E))

        started(7, 4, 70, 0)

        val done = awaitTrip(7)
        assertEquals(TripStatuses.ARRIVED, done.status, done.error)
        assertEquals(BlockPos(4, 70, 0), turtle.pose.pos)
        assertTrue(other.pose.pos.x > 4, "it should have moved past the goal, but is at ${other.pose.pos}")
    }

    @Test
    fun `a turtle standing in the goal moves out of it`() = fleetTest {
        val world = SimWorld.parse(corridorWithPocket)
        val other = simTurtle(8, world, at(4, 70, 0, E))
        val turtle = simTurtle(7, world, at(0, 70, 0, E))

        started(7, 4, 70, 0)

        val done = awaitTrip(7)
        assertEquals(TripStatuses.ARRIVED, done.status, done.error)
        assertEquals(BlockPos(4, 70, 0), turtle.pose.pos)
        assertNotEquals(BlockPos(4, 70, 0), other.pose.pos)
    }

    @Test
    fun `two idle turtles in a tunnel both move on past the goal`() = fleetTest {
        // 8 can only get off the path once 9, further along, has moved on first
        val world = SimWorld.parse("y=70\n" + ".".repeat(13))
        val near = simTurtle(8, world, at(3, 70, 0, E))
        val far = simTurtle(9, world, at(5, 70, 0, E))
        val turtle = simTurtle(7, world, at(0, 70, 0, E))

        started(7, 10, 70, 0)

        val done = awaitTrip(7, timeoutMs = 20_000)
        assertEquals(TripStatuses.ARRIVED, done.status, done.error)
        assertEquals(BlockPos(10, 70, 0), turtle.pose.pos)
        assertTrue(near.pose.pos.x > 10 && far.pose.pos.x > 10, "they're at ${near.pose.pos} and ${far.pose.pos}")
        for (t in listOf(turtle, near, far)) {
            assertFalse(TURTLE_BLOCK in t.obstructions, "turtle ${t.turtleId} bumped into another")
        }
    }

    @Test
    fun `a turtle in the pocket another needs moves further in first`() = fleetTest {
        // a pocket two deep at x 2: 9 is in it, so 8 can only pull over once 9 has gone to the back
        val world = SimWorld.parse(
            """
            y=70
            .......
            ##.####
            ##.####
            """
        )
        val inPocket = simTurtle(9, world, at(2, 70, 1, S))
        val inCorridor = simTurtle(8, world, at(3, 70, 0, E))
        val turtle = simTurtle(7, world, at(0, 70, 0, E))

        started(7, 6, 70, 0)

        val done = awaitTrip(7, timeoutMs = 20_000)
        assertEquals(TripStatuses.ARRIVED, done.status, done.error)
        assertEquals(BlockPos(6, 70, 0), turtle.pose.pos)
        assertEquals(BlockPos(2, 70, 2), inPocket.pose.pos)
        assertEquals(BlockPos(2, 70, 1), inCorridor.pose.pos)
        for (t in listOf(turtle, inCorridor, inPocket)) {
            assertFalse(TURTLE_BLOCK in t.obstructions, "turtle ${t.turtleId} bumped into another")
        }
    }

    @Test
    fun `turtles with nowhere to go in a dead end make the trip fail rather than wait forever`() = fleetTest {
        val world = SimWorld.parse("y=70\n" + ".".repeat(11))
        simTurtle(8, world, at(3, 70, 0, E))
        simTurtle(9, world, at(5, 70, 0, E))
        simTurtle(7, world, at(0, 70, 0, E))

        started(7, 10, 70, 0)

        assertEquals(TripStatuses.FAILED, awaitTrip(7, timeoutMs = 20_000).status)
    }

    @Test
    fun `a short detour is preferred to moving a turtle`() = fleetTest {
        val world = SimWorld.parse(
            """
            y=70
            .....
            .....
            """
        )
        val other = simTurtle(8, world, at(2, 70, 0, E))
        simTurtle(7, world, at(0, 70, 0, E))

        started(7, 4, 70, 0)

        assertEquals(TripStatuses.ARRIVED, awaitTrip(7).status)
        assertTrue(other.pathsReceived.isEmpty(), "it shouldn't have been asked to move")
    }

    @Test
    fun `an offline turtle in the way isn't moved, so there's no way past`() = fleetTest {
        val world = SimWorld.parse(corridorWithPocket)
        disconnect(simTurtle(8, world, at(3, 70, 0, E))) // the server still has it at x 3
        val turtle = simTurtle(7, world, at(0, 70, 0, E))

        started(7, 6, 70, 0)

        val done = awaitTrip(7)
        assertEquals(TripStatuses.FAILED, done.status)
        assertTrue(turtle.pose.pos.x < 3, "it got past, to ${turtle.pose.pos}")
    }

    @Test
    fun `a turtle with no fuel in the way isn't moved`() = fleetTest {
        val world = SimWorld.parse(corridorWithPocket)
        val other = simTurtle(8, world, at(3, 70, 0, E), fuel = 0)
        simTurtle(7, world, at(0, 70, 0, E))

        started(7, 6, 70, 0)

        assertEquals(TripStatuses.FAILED, awaitTrip(7).status)
        assertTrue(other.pathsReceived.isEmpty(), "it shouldn't have been asked to move")
    }

    @Test
    fun `a turtle on a trip of its own isn't moved aside`() = fleetTest {
        // 8 is slowly on its way along the only way 7 could go
        val world = SimWorld.parse("y=70\n" + ".".repeat(12))
        val other = simTurtle(8, world, at(2, 70, 0, E), stepDelayMs = 100)
        simTurtle(7, world, at(0, 70, 0, E))
        started(8, 11, 70, 0)

        started(7, 6, 70, 0)

        val done = awaitTrip(8)
        assertEquals(TripStatuses.ARRIVED, done.status, done.error)
        assertNull(done.makingWayFor)
        assertEquals(1, other.pathsReceived.size, "its trip shouldn't have been interrupted")
        assertEquals(BlockPos(11, 70, 0), other.pose.pos)
    }

    @Test
    fun `a trip waiting for the way to clear can be cancelled`() = fleetTest {
        val world = SimWorld.parse(corridorWithPocket)
        simTurtle(8, world, at(3, 70, 0, E), stepDelayMs = 200)
        val turtle = simTurtle(7, world, at(0, 70, 0, E))
        started(7, 6, 70, 0)
        val waiting = awaitStatus(7, TripStatuses.WAITING)
        assertEquals(listOf(8), waiting.waitingFor)
        val pathsBefore = turtle.pathsReceived.size
        val stoppedAt = turtle.pose

        val response = client.delete("/api/turtles/7/goto")

        assertEquals(HttpStatusCode.Accepted, response.status, response.bodyAsText())
        assertEquals(TripStatuses.CANCELLED, trip(7).status)
        assertEquals(7, trip(8).makingWayFor)
        awaitTrip(8)
        delay(100)
        assertEquals(stoppedAt, turtle.pose)
        assertEquals(pathsBefore, turtle.pathsReceived.size, "it shouldn't have set off again")
    }

    @Test
    fun `a new goto replaces the trip in progress`() = fleetTest {
        val world = SimWorld.parse("y=70\n" + ".".repeat(30))
        val turtle = simTurtle(7, world, at(0, 70, 0, E), stepDelayMs = 20)

        started(7, 29, 70, 0)
        delay(100)
        started(7, 3, 70, 0)

        val done = awaitTrip(7)
        assertEquals(TripStatuses.ARRIVED, done.status, done.error)
        assertEquals(BlockPos(3, 70, 0), turtle.pose.pos)
    }

    @Test
    fun `a path is only followed from the pose it was planned from`() = fleetTest {
        // the turtle moves on its own between the server planning a path and the path arriving
        val world = SimWorld.parse("y=70\n......")
        val turtle = simTurtle(7, world, at(0, 70, 0, E))
        turtle.moveBehindServersBack(at(1, 70, 0, E))

        started(7, 4, 70, 0)

        val done = awaitTrip(7)
        assertEquals(TripStatuses.ARRIVED, done.status, done.error)
        assertEquals(BlockPos(4, 70, 0), turtle.pose.pos)
        assertEquals(1, done.replans, "the first path should have been refused and replanned")
        assertTrue(turtle.pathsReceived.first().steps.size == 4, "the first path was planned from x 0")
    }

    // --- stopping ---

    @Test
    fun `a trip can be cancelled`() = fleetTest {
        val world = SimWorld.parse("y=70\n" + ".".repeat(40))
        val turtle = simTurtle(7, world, at(0, 70, 0, E), stepDelayMs = 30)
        started(7, 39, 70, 0)
        delay(150)

        val response = client.delete("/api/turtles/7/goto")

        assertEquals(HttpStatusCode.Accepted, response.status, response.bodyAsText())
        assertEquals(TripStatuses.CANCELLED, awaitTrip(7).status)
        delay(150)
        assertTrue(turtle.pose.pos.x < 39, "it should have stopped part way, at ${turtle.pose.pos}")
    }

    @Test
    fun `cancelling when there's no trip is not found`() = fleetTest {
        val world = SimWorld.parse("y=70\n...")
        simTurtle(7, world, at(0, 70, 0, E))

        assertEquals(HttpStatusCode.NotFound, client.delete("/api/turtles/7/goto").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/turtles/7/goto").status)
    }

    @Test
    fun `a trip fails if the turtle disconnects`() = fleetTest {
        val world = SimWorld.parse("y=70\n" + ".".repeat(40))
        val turtle = simTurtle(7, world, at(0, 70, 0, E), stepDelayMs = 30)
        started(7, 39, 70, 0)
        delay(100)

        disconnect(turtle)

        val done = awaitTrip(7)
        assertEquals(TripStatuses.FAILED, done.status)
        assertTrue("disconnected" in done.error.orEmpty(), done.error)
    }

    @Test
    fun `a trip fails if the turtle reconnects before the server notices it dropped`() = fleetTest {
        val world = SimWorld.parse("y=70\n" + ".".repeat(40))
        val turtle = simTurtle(7, world, at(0, 70, 0, E), stepDelayMs = 30)
        started(7, 39, 70, 0)
        delay(100)

        reconnect(turtle) // the turtle stops its path, without a result, like main.lua

        val done = awaitTrip(7, timeoutMs = 3_000)
        assertEquals(TripStatuses.FAILED, done.status)
        assertTrue("disconnected" in done.error.orEmpty(), done.error)
        val stoppedAt = turtle.pose
        delay(200)
        assertEquals(stoppedAt, turtle.pose)

        // and it can be sent again on its new connection
        started(7, 39, 70, 0)
        assertEquals(TripStatuses.ARRIVED, awaitTrip(7).status)
        assertEquals(BlockPos(39, 70, 0), turtle.pose.pos)
    }

    // --- refused ---

    @Test
    fun `a goal an offline turtle is standing in is refused`() = fleetTest {
        val world = SimWorld.parse("y=70\n......")
        disconnect(simTurtle(8, world, at(4, 70, 0, E))) // the server still has it at x 4
        simTurtle(7, world, at(0, 70, 0, E))

        val response = goto(7, 4, 70, 0)

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
    }

    @Test
    fun `a trip needing more fuel than the turtle has is refused`() = fleetTest {
        val world = SimWorld.parse("y=70\n.....")
        simTurtle(7, world, at(0, 70, 0, E), fuel = 2)

        val response = goto(7, 4, 70, 0)

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertTrue("fuel" in response.error(), response.error())
    }

    @Test
    fun `a turtle with unlimited fuel isn't limited`() = fleetTest {
        val world = SimWorld.parse("y=70\n.....")
        simTurtle(7, world, at(0, 70, 0, E), fuel = null)

        assertEquals(HttpStatusCode.Accepted, goto(7, 4, 70, 0).status)
        assertEquals(TripStatuses.ARRIVED, awaitTrip(7).status)
    }

    @Test
    fun `an offline turtle can't be sent anywhere`() = fleetTest {
        assertEquals(HttpStatusCode.Conflict, goto(7, 4, 70, 0).status)
    }

    @Test
    fun `a turtle that hasn't been seeded can't be sent anywhere`() = fleetTest {
        turtle(7) {
            sync()
            val response = goto(7, 4, 70, 0)

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertTrue("position" in response.error(), response.error())
        }
    }

    @Test
    fun `a bad heading is refused`() = fleetTest {
        val world = SimWorld.parse("y=70\n...")
        simTurtle(7, world, at(0, 70, 0, E))

        assertEquals(HttpStatusCode.BadRequest, goto(7, 2, 70, 0, heading = "up").status)
    }
}
