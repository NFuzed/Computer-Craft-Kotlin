package app.path

import app.path.Heading.E
import app.path.Heading.N
import app.path.PathAction.*
import app.store.BlockPos
import kotlin.test.*

private val COSTS = PathCosts(move = 1.0, turn = 0.5, digVertical = 2.0, digHorizontal = 11.0, unseen = 0.25)

private fun pose(x: Int, y: Int, z: Int, heading: Heading) = Pose(BlockPos(x, y, z), heading)

/** Plans on [world] with [COSTS], searching only inside the drawing unless [rules] say otherwise. */
private fun plan(
    world: GridWorld,
    start: Pose,
    goal: BlockPos,
    goalHeading: Heading? = null,
    rules: PathRules = PathRules(canDig = true, bounds = world.bounds)
): PathResult = PathPlanner(world, COSTS).plan(start, goal, goalHeading, rules)

/** The path, after checking it's one a turtle could actually follow from [start] under [rules]. */
private fun found(result: PathResult, world: GridWorld, start: Pose, rules: PathRules): Path {
    assertIs<PathResult.Found>(result, "expected a path")
    val path = result.path
    var at = start
    for ((i, step) in path.steps.withIndex()) {
        val expected = when (step.action) {
            FORWARD -> at.copy(pos = at.pos + at.heading.delta)
            BACK -> at.copy(pos = at.pos - at.heading.delta)
            UP -> at.copy(pos = at.pos.copy(y = at.pos.y + 1))
            DOWN -> at.copy(pos = at.pos.copy(y = at.pos.y - 1))
            TURN_LEFT -> at.copy(heading = at.heading.left)
            TURN_RIGHT -> at.copy(heading = at.heading.right)
        }
        assertEquals(expected, step.to, "step $i (${step.action}) ends somewhere else")
        if (step.to.pos != at.pos) {
            val cell = step.to.pos
            val block = world.blockAt(cell)
            assertTrue(cell in rules.bounds, "step $i leaves the bounds at $cell")
            assertFalse(cell in rules.impassable, "step $i enters impassable $cell")
            assertFalse(block != null && BlockRules.isProtected(block), "step $i enters protected $block at $cell")
            if (step.dig) {
                assertTrue(rules.canDig, "step $i digs, but the turtle can't")
                assertFalse(rules.noDigZones.any { cell in it }, "step $i digs in a no-dig zone at $cell")
                assertNotEquals(BACK, step.action, "step $i digs behind the turtle, which it can't")
            } else {
                assertTrue(
                    block == null || BlockRules.isOpen(block),
                    "step $i moves into $block at $cell without digging"
                )
            }
        } else {
            assertFalse(step.dig, "step $i turns but digs")
        }
        at = step.to
    }
    assertEquals(path.steps.count { it.action in setOf(FORWARD, BACK, UP, DOWN) }, path.fuel, "fuel is one per move")
    return path
}

private fun Path.actions() = steps.map { it.action }

class PathPlannerTest {

    // --- basics ---

    @Test
    fun `already there is an empty path`() {
        val world = GridWorld.parse("y=0\n...")
        val start = pose(1, 0, 0, E)

        val path = found(plan(world, start, BlockPos(1, 0, 0)), world, start, PathRules(true, bounds = world.bounds))

        assertEquals(emptyList(), path.steps)
        assertEquals(0.0, path.cost)
    }

    @Test
    fun `in a straight line it just moves forward`() {
        val world = GridWorld.parse("y=0\n....")
        val start = pose(0, 0, 0, E)

        val path = found(plan(world, start, BlockPos(3, 0, 0)), world, start, PathRules(true, bounds = world.bounds))

        assertEquals(listOf(FORWARD, FORWARD, FORWARD), path.actions())
        assertEquals(3, path.fuel)
        assertEquals(3.0, path.cost)
    }

    @Test
    fun `it turns towards the goal`() {
        val world = GridWorld.parse(
            """
            y=0
            ...
            ...
            ...
            """
        )
        val start = pose(0, 0, 0, E)

        val path = found(plan(world, start, BlockPos(0, 0, 2)), world, start, PathRules(true, bounds = world.bounds))

        assertEquals(listOf(TURN_RIGHT, FORWARD, FORWARD), path.actions()) // E -> S, then +z twice
    }

    @Test
    fun `it ends facing the requested heading`() {
        val world = GridWorld.parse("y=0\n...")
        val start = pose(0, 0, 0, E)

        val path = found(
            plan(world, start, BlockPos(2, 0, 0), goalHeading = N), world, start, PathRules(true, bounds = world.bounds)
        )

        assertEquals(pose(2, 0, 0, N), path.steps.last().to)
    }

    @Test
    fun `it backs up into known air instead of turning around`() {
        val world = GridWorld.parse("y=0\n...")
        val start = pose(2, 0, 0, E)

        val path = found(plan(world, start, BlockPos(0, 0, 0)), world, start, PathRules(true, bounds = world.bounds))

        assertEquals(listOf(BACK, BACK), path.actions())
    }

    @Test
    fun `it doesn't back up into unseen cells, since it can't see or dig behind it`() {
        val world = GridWorld.parse("y=0\n??.")
        val start = pose(2, 0, 0, E)

        val path = found(plan(world, start, BlockPos(0, 0, 0)), world, start, PathRules(true, bounds = world.bounds))

        assertFalse(BACK in path.actions())
    }

    @Test
    fun `it moves up and down`() {
        val world = GridWorld.parse(
            """
            y=0
            .
            y=1
            .
            y=2
            .
            """
        )
        val start = pose(0, 0, 0, N)

        val path = found(plan(world, start, BlockPos(0, 2, 0)), world, start, PathRules(true, bounds = world.bounds))

        assertEquals(listOf(UP, UP), path.actions())
    }

    @Test
    fun `turtles move through water without digging`() {
        val world = GridWorld.parse("y=0\n.~~.")
        val start = pose(0, 0, 0, E)

        val path = found(plan(world, start, BlockPos(3, 0, 0)), world, start, PathRules(true, bounds = world.bounds))

        assertEquals(listOf(FORWARD, FORWARD, FORWARD), path.actions())
        assertTrue(path.steps.none { it.dig })
    }

    // --- digging ---

    @Test
    fun `it walks around a wall when the detour is cheaper than digging through`() {
        // digging straight through costs 4 moves + 11 = 15; going round row 2 costs 8 moves + 3 turns = 9.5
        val world = GridWorld.parse(
            """
            y=0
            ..#..
            ..#..
            .....
            """
        )
        val start = pose(0, 0, 0, E)

        val path = found(plan(world, start, BlockPos(4, 0, 0)), world, start, PathRules(true, bounds = world.bounds))

        assertTrue(path.steps.none { it.dig }, "expected a detour, got ${path.actions()}")
    }

    @Test
    fun `it digs through a wall when the detour is longer than digging`() {
        // digging costs 8 moves + 11 = 19; going round row 6 costs 20 moves + 3 turns = 21.5
        val world = GridWorld.parse(
            """
            y=0
            ....#....
            ....#....
            ....#....
            ....#....
            ....#....
            ....#....
            .........
            """
        )
        val start = pose(0, 0, 0, E)

        val path = found(plan(world, start, BlockPos(8, 0, 0)), world, start, PathRules(true, bounds = world.bounds))

        assertEquals(List(8) { FORWARD }, path.actions())
        assertEquals(listOf(BlockPos(4, 0, 0)), path.steps.filter { it.dig }.map { it.to.pos })
        assertEquals(8.0 + 11.0, path.cost)
    }

    @Test
    fun `it prefers digging up to open air over tunnelling sideways`() {
        // tunnelling: 11 moves, 10 of them dug sideways. Up and over: dig up, climb, 11 across, dig down, drop
        val world = GridWorld.parse(
            """
            y=0
            .##########.
            y=1
            ############
            y=2
            ............
            """
        )
        val start = pose(0, 0, 0, E)

        val path = found(plan(world, start, BlockPos(11, 0, 0)), world, start, PathRules(true, bounds = world.bounds))

        assertTrue(path.steps.none { it.dig && it.action == FORWARD }, "dug sideways: ${path.actions()}")
        assertEquals(
            listOf(UP, UP) + List(11) { FORWARD } + listOf(DOWN, DOWN),
            path.actions()
        )
        assertEquals(listOf(BlockPos(0, 1, 0), BlockPos(11, 1, 0)), path.steps.filter { it.dig }.map { it.to.pos })
    }

    @Test
    fun `a turtle that can't dig goes around solid blocks, however far`() {
        val world = GridWorld.parse(
            """
            y=0
            ....#....
            ....#....
            .........
            """
        )
        val start = pose(0, 0, 0, E)
        val rules = PathRules(canDig = false, bounds = world.bounds)

        val path = found(plan(world, start, BlockPos(8, 0, 0), rules = rules), world, start, rules)

        assertTrue(path.steps.none { it.dig })
    }

    @Test
    fun `it never digs in a no-dig zone`() {
        // without the zone, digging through (19) would beat going round (21.5), as in the test above
        val world = GridWorld.parse(
            """
            y=0
            ....#....
            ....#....
            ....#....
            ....#....
            ....#....
            ....#....
            .........
            """
        )
        val start = pose(0, 0, 0, E)
        val zone = Box(BlockPos(4, 0, 0), BlockPos(4, 0, 5))
        val rules = PathRules(canDig = true, noDigZones = listOf(zone), bounds = world.bounds)

        val path = found(plan(world, start, BlockPos(8, 0, 0), rules = rules), world, start, rules)

        assertTrue(path.steps.none { it.dig }, "dug in the zone: ${path.actions()}")
    }

    @Test
    fun `it never digs protected blocks such as chests and bedrock`() {
        // digging through (19) would beat going round row 5 (18 moves + 3 turns = 19.5)
        val world = GridWorld.parse(
            """
            y=0
            ....C....
            ....B....
            ....C....
            ....B....
            ....C....
            .........
            """
        )
        val start = pose(0, 0, 0, E)
        val rules = PathRules(canDig = true, bounds = world.bounds)

        found(plan(world, start, BlockPos(8, 0, 0), rules = rules), world, start, rules) // checks protected blocks
    }

    // --- other turtles and limits ---

    @Test
    fun `it never enters impassable cells, even in open air`() {
        // another turtle sits at (2, 0, 0)
        val world = GridWorld.parse(
            """
            y=0
            .....
            .....
            """
        )
        val start = pose(0, 0, 0, E)
        val rules = PathRules(canDig = true, impassable = setOf(BlockPos(2, 0, 0)), bounds = world.bounds)

        val path = found(plan(world, start, BlockPos(4, 0, 0), rules = rules), world, start, rules)

        assertTrue(path.steps.any { it.to.pos.z == 1 }, "expected it to go round via row 1")
    }

    @Test
    fun `it goes through a turtle that can move when there's no other way`() {
        val world = GridWorld.parse("y=0\n.....")
        val start = pose(0, 0, 0, E)
        val rules = PathRules(canDig = false, movable = setOf(BlockPos(2, 0, 0)), bounds = world.bounds)

        val path = found(plan(world, start, BlockPos(4, 0, 0), rules = rules), world, start, rules)

        assertEquals(List(4) { FORWARD }, path.actions())
        assertEquals(4 * COSTS.move + COSTS.nudge, path.cost)
    }

    @Test
    fun `it goes round a turtle that can move when that's shorter than waiting for it`() {
        val world = GridWorld.parse(
            """
            y=0
            .....
            .....
            """
        )
        val start = pose(0, 0, 0, E)
        val rules = PathRules(canDig = false, movable = setOf(BlockPos(2, 0, 0)), bounds = world.bounds)

        val path = found(plan(world, start, BlockPos(4, 0, 0), rules = rules), world, start, rules)

        assertTrue(path.steps.none { it.to.pos == BlockPos(2, 0, 0) }, "it went through the turtle: ${path.actions()}")
    }

    @Test
    fun `out of the way is the nearest cell off the other's path`() {
        // the other turtle's path runs along row 0; the only way off it is the pocket at x 3
        val world = GridWorld.parse(
            """
            y=0
            ......
            ###.##
            """
        )
        val start = pose(1, 0, 0, E)
        val avoid = (0..5).map { BlockPos(it, 0, 0) }.toSet()
        val rules = PathRules(canDig = false, bounds = world.bounds)

        val path = found(PathPlanner(world, COSTS).planAway(start, avoid, rules), world, start, rules)

        assertEquals(BlockPos(3, 0, 1), path.steps.last().to.pos)
        assertEquals(
            PathResult.Found(Path(emptyList(), 0.0)),
            PathPlanner(world, COSTS).planAway(start, emptySet(), rules)
        )
        val noRoom = PathRules(canDig = false, impassable = setOf(BlockPos(3, 0, 1)), bounds = world.bounds)
        assertEquals(PathResult.Unreachable, PathPlanner(world, COSTS).planAway(start, avoid, noRoom))
    }

    @Test
    fun `it is unreachable when walled in and it can't dig`() {
        val world = GridWorld.parse("y=0\n.#.")

        val result = plan(world, pose(0, 0, 0, E), BlockPos(2, 0, 0), rules = PathRules(false, bounds = world.bounds))

        assertEquals(PathResult.Unreachable, result)
    }

    @Test
    fun `a goal that is impassable or outside the bounds is unreachable`() {
        val world = GridWorld.parse("y=0\n...")
        val start = pose(0, 0, 0, E)

        val blocked = PathRules(true, impassable = setOf(BlockPos(2, 0, 0)), bounds = world.bounds)
        assertEquals(PathResult.Unreachable, plan(world, start, BlockPos(2, 0, 0), rules = blocked))
        assertEquals(PathResult.Unreachable, plan(world, start, BlockPos(5, 0, 0)))
    }

    @Test
    fun `it gives up after searching too many states`() {
        val world = GridWorld.parse("y=0\n.")
        val huge = Box(BlockPos(-100, -100, -100), BlockPos(100, 100, 100))
        val rules = PathRules(canDig = true, bounds = huge, maxNodes = 50)

        assertEquals(PathResult.TooFar, plan(world, pose(0, 0, 0, E), BlockPos(90, 90, 90), rules = rules))
    }

    // --- unseen cells ---

    @Test
    fun `unseen cells that are probably air are crossed without planning to dig`() {
        val world = GridWorld.parse("y=0\n.???.")
        val start = pose(0, 0, 0, E)

        val path = found(plan(world, start, BlockPos(4, 0, 0)), world, start, PathRules(true, bounds = world.bounds))

        assertEquals(List(4) { FORWARD }, path.actions())
        assertTrue(path.steps.none { it.dig })
        assertEquals(4.0 + 3 * 0.25, path.cost)
    }

    @Test
    fun `unseen cells that are probably solid are planned as digs`() {
        val world = GridWorld.parse("y=0\n.?.").withProbablySolid { true }
        val start = pose(0, 0, 0, E)

        val path = found(plan(world, start, BlockPos(2, 0, 0)), world, start, PathRules(true, bounds = world.bounds))

        assertEquals(listOf(true, false), path.steps.map { it.dig })
        assertEquals(2.0 + 11.0 + 0.25, path.cost)
    }

    @Test
    fun `known air is preferred over unseen cells at the same distance`() {
        // round row 0 and round row 2 take the same moves and turns, but row 2 has two unseen cells
        val world = GridWorld.parse(
            """
            y=0
            ....
            .##.
            .??.
            """
        )
        val start = pose(0, 0, 1, E)

        val path = found(plan(world, start, BlockPos(3, 0, 1)), world, start, PathRules(true, bounds = world.bounds))

        assertTrue(path.steps.none { it.to.pos.z == 2 }, "went round through the unseen cells: ${path.actions()}")
    }

    @Test
    fun `an unseen cell that is probably solid in a no-dig zone is avoided when there is another way`() {
        val world = GridWorld.parse(
            """
            y=0
            .?.
            ...
            """
        ).withProbablySolid { true }
        val start = pose(0, 0, 0, E)
        val zone = Box(BlockPos(1, 0, 0), BlockPos(1, 0, 0))
        val rules = PathRules(canDig = true, noDigZones = listOf(zone), bounds = world.bounds)

        val path = found(plan(world, start, BlockPos(2, 0, 0), rules = rules), world, start, rules)

        assertTrue(path.steps.none { it.to.pos == BlockPos(1, 0, 0) })
    }
}
