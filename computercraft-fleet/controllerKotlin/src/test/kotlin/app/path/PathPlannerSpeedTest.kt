package app.path

import app.store.BlockPos
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.measureTimedValue

/**
 * Rough timings on sizes like real trips, so a slow change shows up. The limits are generous, since test machines
 * vary; they catch the planner getting many times slower, not small changes.
 */
class PathPlannerSpeedTest {

    /** Nothing seen yet: everything is guessed, the way it is for a new turtle. */
    private val unknown = object : WorldView {
        override fun blockAt(pos: BlockPos): String? = null
        override fun probablySolid(pos: BlockPos) = pos.y < 64 // underground
    }

    private val bounds = Box(BlockPos(-150, 0, -150), BlockPos(150, 128, 150))

    @Test
    fun `a 200 block trip across open ground plans quickly`() {
        val (result, time) = measureTimedValue {
            PathPlanner(unknown).plan(
                Pose(BlockPos(0, 70, 0), Heading.E), BlockPos(120, 70, 80), rules = PathRules(true, bounds = bounds)
            )
        }

        assertIs<PathResult.Found>(result)
        println("200 block trip: $time")
        assertTrue(time.inWholeMilliseconds < 2_000, "took $time")
    }

    @Test
    fun `a trip deep underground through unexplored ground goes up and over, and plans in time`() {
        // the worst case: everything around is guessed solid, so the cheap way (up to open air, across, back down)
        // is far from the straight line. Bounds are a box round start and goal that reaches the surface, as the
        // service will choose them
        val tight = Box(BlockPos(-16, 4, -16), BlockPos(76, 80, 16))
        val (result, time) = measureTimedValue {
            PathPlanner(unknown).plan(
                Pose(BlockPos(0, 20, 0), Heading.E), BlockPos(60, 20, 0), rules = PathRules(true, bounds = tight)
            )
        }

        assertIs<PathResult.Found>(result)
        val path = result.path
        println("underground trip: $time, ${path.steps.size} steps, ${path.steps.count { it.dig }} digs")
        assertTrue(path.steps.none { it.dig && it.action == PathAction.FORWARD }, "tunnelled sideways")
        assertTrue(time.inWholeMilliseconds < 5_000, "took $time")
    }

    @Test
    fun `a hopeless search gives up at the node limit quickly`() {
        val sealed = object : WorldView {
            override fun blockAt(pos: BlockPos): String? =
                if (pos == BlockPos(100, 70, 0)) "minecraft:bedrock" else null

            override fun probablySolid(pos: BlockPos) = false
        }
        val (result, time) = measureTimedValue {
            PathPlanner(sealed).plan(
                Pose(BlockPos(0, 70, 0), Heading.E), BlockPos(100, 70, 0),
                rules = PathRules(true, bounds = bounds)
            )
        }

        println("hopeless search: $result in $time")
        assertTrue(time.inWholeMilliseconds < 5_000, "took $time")
    }
}
