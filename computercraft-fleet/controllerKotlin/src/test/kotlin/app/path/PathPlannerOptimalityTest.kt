package app.path

import app.store.BlockPos
import protocol.api.AIR
import java.util.*
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Checks A* finds the cheapest path, not just a path, by comparing it with a plain uniform-cost search (Dijkstra, no
 * heuristic) written separately here from the rules in [PathPlanner]'s docs, on many random small worlds.
 */
class PathPlannerOptimalityTest {

    private val costs = PathCosts()

    @Test
    fun `A-star costs match an exhaustive search on random worlds`() {
        val random = Random(42) // fixed, so a failure can be reproduced
        var compared = 0
        repeat(300) { round ->
            val world = randomWorld(random, sizeX = 6, sizeY = 3, sizeZ = 6)
            val bounds = Box(BlockPos(0, 0, 0), BlockPos(5, 2, 5))
            val start = Pose(BlockPos(0, 0, 0), Heading.entries.random(random))
            val goal = BlockPos(5, random.nextInt(3), 5)
            val rules = PathRules(
                canDig = random.nextBoolean(),
                impassable = List(3) { BlockPos(random.nextInt(6), random.nextInt(3), random.nextInt(6)) }
                    .filter { it != start.pos && it != goal }.toSet(),
                noDigZones = if (random.nextBoolean()) listOf(
                    Box(
                        BlockPos(2, 0, 0),
                        BlockPos(3, 2, 5)
                    )
                ) else emptyList(),
                bounds = bounds
            )
            val goalHeading = if (random.nextBoolean()) Heading.entries.random(random) else null

            val expected = cheapest(world, start, goal, goalHeading, rules)
            val result = PathPlanner(world, costs).plan(start, goal, goalHeading, rules)

            if (expected == null) {
                assertEquals(PathResult.Unreachable, result, "round $round")
            } else {
                assertIs<PathResult.Found>(result, "round $round")
                assertEquals(expected, result.path.cost, 1e-9, "round $round")
                compared++
            }
        }
        assert(compared > 100) { "too few reachable worlds to mean much: $compared" }
    }

    /** Air, stone, water, chests and unseen cells at random; start and goal cells are air. */
    private fun randomWorld(random: Random, sizeX: Int, sizeY: Int, sizeZ: Int): GridWorld {
        val drawing = buildString {
            for (y in 0 until sizeY) {
                appendLine("y=$y")
                for (z in 0 until sizeZ) {
                    appendLine(CharArray(sizeX) { x ->
                        if ((x == 0 && y == 0 && z == 0) || (x == sizeX - 1 && z == sizeZ - 1)) '.'
                        else "....####~C??"[random.nextInt(12)]
                    }.concatToString())
                }
            }
        }
        val solidGuess = random.nextBoolean()
        return GridWorld.parse(drawing).withProbablySolid { solidGuess }
    }

    /** The cheapest cost from [start] to [goal], or null if there's no way, by trying everything in cost order. */
    private fun cheapest(
        world: GridWorld,
        start: Pose,
        goal: BlockPos,
        goalHeading: Heading?,
        rules: PathRules
    ): Double? {
        val best = HashMap<Pose, Double>().apply { put(start, 0.0) }
        val queue = PriorityQueue<Pair<Pose, Double>>(compareBy { it.second }).apply { add(start to 0.0) }
        while (queue.isNotEmpty()) {
            val (pose, cost) = queue.poll()
            if (cost > best.getValue(pose)) continue
            if (pose.pos == goal && (goalHeading == null || pose.heading == goalHeading)) return cost
            for ((next, stepCost) in moves(world, pose, rules)) {
                if (cost + stepCost < (best[next] ?: Double.MAX_VALUE)) {
                    best[next] = cost + stepCost
                    queue.add(next to cost + stepCost)
                }
            }
        }
        return null
    }

    private fun moves(world: GridWorld, pose: Pose, rules: PathRules): List<Pair<Pose, Double>> {
        val up = BlockPos(0, 1, 0)
        val result = mutableListOf(
            pose.copy(heading = pose.heading.left) to costs.turn,
            pose.copy(heading = pose.heading.right) to costs.turn
        )

        fun usable(cell: BlockPos) = cell in rules.bounds && cell !in rules.impassable
        fun enter(cell: BlockPos, dig: Double) {
            if (!usable(cell)) return
            val mayDig = rules.canDig && rules.noDigZones.none { cell in it }
            val block = world.blockAt(cell)
            val cost = when {
                block == null && !world.probablySolid(cell) -> costs.move + costs.unseen
                block == null && mayDig -> costs.move + costs.unseen + dig
                block == null -> costs.move + costs.unseen + costs.unseenNoDig
                block == AIR || block == "minecraft:water" -> costs.move
                block == "minecraft:chest" || !mayDig -> return
                else -> costs.move + dig
            }
            result += pose.copy(pos = cell) to cost
        }
        enter(pose.pos + pose.heading.delta, costs.digHorizontal)
        enter(pose.pos + up, costs.digVertical)
        enter(pose.pos - up, costs.digVertical)
        val behind = pose.pos - pose.heading.delta
        val behindBlock = world.blockAt(behind)
        if (usable(behind) && (behindBlock == AIR || behindBlock == "minecraft:water")) {
            result += pose.copy(pos = behind) to costs.move + costs.back
        }
        return result
    }
}
