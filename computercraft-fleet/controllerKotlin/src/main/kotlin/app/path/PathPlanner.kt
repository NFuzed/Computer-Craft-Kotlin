package app.path

import app.store.BlockPos
import kotlin.math.abs

/** What the planner knows about the world. */
interface WorldView {
    /** The block last seen at [pos], or null if nobody has seen it. */
    fun blockAt(pos: BlockPos): String?

    /** For a cell nobody has seen: whether it's probably solid (e.g. underground) rather than air. */
    fun probablySolid(pos: BlockPos): Boolean
}

/**
 * What each step costs, in moves. Only moves use fuel; the rest is how much we'd rather avoid something: a turtle
 * takes a detour of up to [digHorizontal] extra moves rather than dig one block sideways, which keeps the world tidy
 * and makes turtles go up or down to open space instead of tunnelling.
 */
data class PathCosts(
    val move: Double = 1.0,
    val turn: Double = 0.5,             // free on fuel, but takes as long as a move
    val digVertical: Double = 2.0,      // extra, on top of the move, to dig up or down first
    val digHorizontal: Double = 11.0,   // extra, on top of the move, to dig in front first
    val unseen: Double = 0.01,          // extra for a cell nobody has seen, so known air wins a tie. Small, since
    // the heuristic can't count it: a bigger one makes long searches crawl
    val back: Double = 0.1              // extra for backing up, so facing where it goes (and seeing it) wins a tie
) {
    init {
        // the heuristic counts each remaining block as at least one move, which must not overestimate
        require(move > 0 && turn >= 0 && digVertical >= 0 && digHorizontal >= 0 && unseen >= 0 && back >= 0)
    }
}

/**
 * What one turtle may do on one trip.
 * - [canDig]: the turtle has a digging tool
 * - [impassable]: cells it must never enter, even if they're air: other turtles, their reserved cells, station chests
 * - [noDigZones]: solid blocks in these are never dug
 * - [bounds]: the search never leaves this box, so a hopeless search can't wander the whole world
 * - [maxNodes]: give up after expanding this many states. A trip that starts and ends deep underground, through an
 *   unexplored map, needs about a million (around half a second, and roughly 100 MB while it runs)
 */
data class PathRules(
    val canDig: Boolean,
    val impassable: Set<BlockPos> = emptySet(),
    val noDigZones: List<Box> = emptyList(),
    val bounds: Box,
    val maxNodes: Int = 1_500_000
)

/** One turtle API call. [FORWARD], [UP] and [DOWN] may dig first; [BACK] can't, as the turtle can't see behind it. */
enum class PathAction { FORWARD, BACK, UP, DOWN, TURN_LEFT, TURN_RIGHT }

/**
 * One step of a path. [to] is where the turtle is after it. [dig] means the cell is expected to be solid, so the
 * turtle digs it first; otherwise a block in the way means the map was wrong and the path needs replanning.
 */
data class PathStep(val action: PathAction, val to: Pose, val dig: Boolean = false)

data class Path(val steps: List<PathStep>, val cost: Double) {
    /** Fuel the path uses: one per move. Turns and digs are free. */
    val fuel: Int get() = steps.count { it.action in MOVES }

    private companion object {
        val MOVES = setOf(PathAction.FORWARD, PathAction.BACK, PathAction.UP, PathAction.DOWN)
    }
}

sealed interface PathResult {
    data class Found(val path: Path) : PathResult

    /** No path exists inside the bounds under the rules. */
    data object Unreachable : PathResult

    /** Gave up after [PathRules.maxNodes] states; a path may still exist. */
    data object TooFar : PathResult
}

/**
 * Plans a turtle's route with A* over (cell, heading) states, using [PathCosts]. Known air and liquids are open; solid
 * blocks are dug when allowed and worth it; cells nobody has seen are guessed with [WorldView.probablySolid].
 * Paths are optimal for these costs, for what the map knows.
 */
class PathPlanner(private val world: WorldView, private val costs: PathCosts = PathCosts()) {

    fun plan(start: Pose, goal: BlockPos, goalHeading: Heading? = null, rules: PathRules): PathResult {
        if (goal !in rules.bounds || goal in rules.impassable) return PathResult.Unreachable
        Search(goal, goalHeading, rules).run {
            return run(start)
        }
    }

    /**
     * One search. States are (cell, heading) packed into a Long relative to the bounds; see [key]. Kept in
     * primitive tables, since a long trip can visit millions of them.
     */
    private inner class Search(val goal: BlockPos, val goalHeading: Heading?, val rules: PathRules) {
        private val min = rules.bounds.min
        private val sizeY = (rules.bounds.max.y - min.y + 1).toLong()
        private val sizeZ = (rules.bounds.max.z - min.z + 1).toLong()
        private val table = StateTable()
        private val open = OpenHeap()

        // set by enter(): whether the move it just costed digs first
        private var lastDig = false

        fun run(start: Pose): PathResult {
            val startKey = key(start.pos.x, start.pos.y, start.pos.z, start.heading.ordinal)
            table.reach(table.slot(startKey), 0.0, 0, false)
            open.push(heuristic(start.pos.x, start.pos.y, start.pos.z), 0.0, startKey)
            var expanded = 0
            while (open.size > 0) {
                open.pop()
                val key = open.poppedKey
                val cost = open.poppedCost
                val slot = table.slot(key)
                if (table.isDone(slot)) continue // a cheaper copy was already expanded
                table.markDone(slot)

                val pose = unpack(key)
                if (isGoal(pose)) return PathResult.Found(pathTo(key, startKey, cost))
                if (++expanded > rules.maxNodes) return PathResult.TooFar
                expand(pose, cost)
            }
            return PathResult.Unreachable
        }

        private fun isGoal(pose: Pose) = pose.pos == goal && (goalHeading == null || pose.heading == goalHeading)

        /** Tries every step the turtle could take from [pose], reached for [cost]. */
        private fun expand(pose: Pose, cost: Double) {
            val (x, y, z) = pose.pos
            val heading = pose.heading
            val h = heading.ordinal
            val dx = heading.delta.x
            val dz = heading.delta.z
            relax(cost, costs.turn, x, y, z, heading.left.ordinal, PathAction.TURN_LEFT, false)
            relax(cost, costs.turn, x, y, z, heading.right.ordinal, PathAction.TURN_RIGHT, false)
            // arguments are evaluated in order, so lastDig is read after enter() has set it
            val forward = enter(x + dx, y, z + dz, costs.digHorizontal)
            relax(cost, forward, x + dx, y, z + dz, h, PathAction.FORWARD, lastDig)
            relax(cost, enter(x, y + 1, z, costs.digVertical), x, y + 1, z, h, PathAction.UP, lastDig)
            relax(cost, enter(x, y - 1, z, costs.digVertical), x, y - 1, z, h, PathAction.DOWN, lastDig)
            relax(cost, back(x - dx, y, z - dz), x - dx, y, z - dz, h, PathAction.BACK, false)
        }

        /**
         * Records reaching the state by [action], for [cost] plus [stepCost], if that's cheaper than before, and queues
         * it. A NaN [stepCost] means the step isn't possible.
         */
        private fun relax(
            cost: Double, stepCost: Double, x: Int, y: Int, z: Int, heading: Int, action: PathAction, dig: Boolean
        ) {
            if (stepCost.isNaN()) return
            val total = cost + stepCost
            val key = key(x, y, z, heading)
            val slot = table.slot(key)
            if (table.isDone(slot) || total >= table.cost(slot)) return
            table.reach(slot, total, action.ordinal, dig)
            open.push(total + heuristic(x, y, z), total, key)
        }

        /** Packs a state. Only called for cells inside the bounds, so every key is non-negative. */
        private fun key(x: Int, y: Int, z: Int, heading: Int): Long =
            (((x - min.x) * sizeY + (y - min.y)) * sizeZ + (z - min.z)) * 4 + heading

        private fun unpack(key: Long): Pose {
            val cell = key / 4
            return Pose(
                BlockPos(
                    (cell / sizeZ / sizeY).toInt() + min.x,
                    (cell / sizeZ % sizeY).toInt() + min.y,
                    (cell % sizeZ).toInt() + min.z
                ),
                Heading.entries[(key % 4).toInt()]
            )
        }

        /** Each block still to go needs at least one move. */
        private fun heuristic(x: Int, y: Int, z: Int) =
            (abs(x - goal.x) + abs(y - goal.y) + abs(z - goal.z)) * costs.move

        /**
         * The cost of moving into the cell, digging it first (for [digCost] extra) if it's solid and that's allowed,
         * or NaN if the turtle can't. Sets [lastDig].
         */
        private fun enter(x: Int, y: Int, z: Int, digCost: Double): Double {
            val cell = BlockPos(x, y, z)
            lastDig = false
            if (cell !in rules.bounds || cell in rules.impassable) return Double.NaN
            val mayDig = rules.canDig && rules.noDigZones.none { cell in it }
            val block = world.blockAt(cell)
            return when {
                block == null -> {
                    val unseen = costs.move + costs.unseen
                    when {
                        !world.probablySolid(cell) -> unseen
                        mayDig -> unseen + digCost.also { lastDig = true }
                        // can't dig it, but it might be air after all: allowed, but only when nothing else works
                        else -> unseen + costs.digHorizontal
                    }
                }

                BlockRules.isOpen(block) -> costs.move
                BlockRules.isProtected(block) || !mayDig -> Double.NaN
                else -> costs.move + digCost.also { lastDig = true }
            }
        }

        /** Backing up, only into cells known to be open, since the turtle can't see or dig behind it; or NaN. */
        private fun back(x: Int, y: Int, z: Int): Double {
            val cell = BlockPos(x, y, z)
            if (cell !in rules.bounds || cell in rules.impassable) return Double.NaN
            val block = world.blockAt(cell) ?: return Double.NaN
            return if (BlockRules.isOpen(block)) costs.move + costs.back else Double.NaN
        }

        private fun pathTo(endKey: Long, startKey: Long, cost: Double): Path {
            val steps = ArrayDeque<PathStep>()
            var key = endKey
            while (key != startKey) {
                val info = table.info(table.slot(key))
                val step = PathStep(
                    PathAction.entries[info and StateTable.ACTION_MASK], unpack(key), info and StateTable.DIG != 0
                )
                steps.addFirst(step)
                val before = previous(step)
                key = key(before.pos.x, before.pos.y, before.pos.z, before.heading.ordinal)
            }
            return Path(steps.toList(), cost)
        }

        /** Where the turtle was before [step]. */
        private fun previous(step: PathStep): Pose = with(step.to) {
            when (step.action) {
                PathAction.FORWARD -> copy(pos = pos - heading.delta)
                PathAction.BACK -> copy(pos = pos + heading.delta)
                PathAction.UP -> copy(pos = pos - UP)
                PathAction.DOWN -> copy(pos = pos + UP)
                PathAction.TURN_LEFT -> copy(heading = heading.right)
                PathAction.TURN_RIGHT -> copy(heading = heading.left)
            }
        }
    }

    private companion object {
        val UP = BlockPos(0, 1, 0)
    }
}
