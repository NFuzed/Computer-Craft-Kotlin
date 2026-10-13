package app.path

// Primitive collections for PathPlanner's search. A trip can visit millions of states, and boxing each one into
// objects for HashMap and PriorityQueue made the search several times slower.

/**
 * Per-state search data keyed by a non-negative Long: the cheapest cost found so far, the step that reached it, and
 * whether it's been expanded. Open addressing with linear probing; grows at half full.
 */
internal class StateTable(initialCapacity: Int = 1 shl 16) {
    private var keys = LongArray(initialCapacity) { EMPTY }
    private var costs = DoubleArray(initialCapacity)
    private var infos = ByteArray(initialCapacity)
    private var size = 0

    /** The slot for [key], adding it (with an infinite cost) if it's new. Slots move when the table grows. */
    fun slot(key: Long): Int {
        if (size * 2 >= keys.size) grow()
        var i = index(key, keys.size)
        while (true) {
            when (keys[i]) {
                key -> return i
                EMPTY -> {
                    keys[i] = key
                    costs[i] = Double.POSITIVE_INFINITY
                    infos[i] = 0
                    size++
                    return i
                }
            }
            i = (i + 1) and (keys.size - 1)
        }
    }

    fun cost(slot: Int) = costs[slot]
    fun info(slot: Int) = infos[slot].toInt()
    fun isDone(slot: Int) = infos[slot].toInt() and DONE != 0

    /** Records that the state was reached for [cost] by [action] (a [PathAction] ordinal), digging first if [dig]. */
    fun reach(slot: Int, cost: Double, action: Int, dig: Boolean) {
        costs[slot] = cost
        infos[slot] = (action or (if (dig) DIG else 0) or REACHED).toByte()
    }

    fun markDone(slot: Int) {
        infos[slot] = (infos[slot].toInt() or DONE).toByte()
    }

    private fun grow() {
        val oldKeys = keys
        val oldCosts = costs
        val oldInfos = infos
        keys = LongArray(oldKeys.size * 2) { EMPTY }
        costs = DoubleArray(keys.size)
        infos = ByteArray(keys.size)
        for (j in oldKeys.indices) {
            if (oldKeys[j] == EMPTY) continue
            var i = index(oldKeys[j], keys.size)
            while (keys[i] != EMPTY) i = (i + 1) and (keys.size - 1)
            keys[i] = oldKeys[j]
            costs[i] = oldCosts[j]
            infos[i] = oldInfos[j]
        }
    }

    private fun index(key: Long, capacity: Int): Int {
        val h = key * -0x61c8864680b583ebL // Fibonacci hashing spreads neighbouring states apart
        return (h ushr 32).toInt() and (capacity - 1)
    }

    companion object {
        private const val EMPTY = -1L

        // info bits: the action's ordinal in the low 3 bits, then flags
        const val ACTION_MASK = 0b111
        const val DIG = 1 shl 3
        const val REACHED = 1 shl 4
        const val DONE = 1 shl 5
    }
}

/**
 * A binary min-heap of (estimate, cost, key), ordered by estimate; on a tie the higher cost (further along) first,
 * then the earliest pushed, so the search is repeatable.
 */
internal class OpenHeap(initialCapacity: Int = 1 shl 14) {
    private var estimates = DoubleArray(initialCapacity)
    private var costs = DoubleArray(initialCapacity)
    private var keys = LongArray(initialCapacity)
    private var seqs = LongArray(initialCapacity)
    private var nextSeq = 0L

    var size = 0
        private set

    // the entry popped last
    var poppedCost = 0.0
        private set
    var poppedKey = 0L
        private set

    fun push(estimate: Double, cost: Double, key: Long) {
        if (size == keys.size) grow()
        var i = size++
        while (i > 0) {
            val parent = (i - 1) / 2
            if (!before(estimate, cost, nextSeq, parent)) break
            move(parent, i)
            i = parent
        }
        set(i, estimate, cost, key, nextSeq++)
    }

    /** Removes the first entry; read it from [poppedCost] and [poppedKey]. */
    fun pop() {
        poppedCost = costs[0]
        poppedKey = keys[0]
        size--
        if (size == 0) return
        val e = estimates[size]
        val c = costs[size]
        val k = keys[size]
        val s = seqs[size]
        var i = 0
        while (true) {
            var child = 2 * i + 1
            if (child >= size) break
            if (child + 1 < size && before(child + 1, child)) child++
            if (!before(estimates[child], costs[child], seqs[child], e, c, s)) break
            move(child, i)
            i = child
        }
        set(i, e, c, k, s)
    }

    private fun before(e: Double, c: Double, s: Long, slot: Int) =
        before(e, c, s, estimates[slot], costs[slot], seqs[slot])

    private fun before(a: Int, b: Int) = before(estimates[a], costs[a], seqs[a], estimates[b], costs[b], seqs[b])

    private fun before(e1: Double, c1: Double, s1: Long, e2: Double, c2: Double, s2: Long) = when {
        e1 != e2 -> e1 < e2
        c1 != c2 -> c1 > c2
        else -> s1 < s2
    }

    private fun move(from: Int, to: Int) = set(to, estimates[from], costs[from], keys[from], seqs[from])

    private fun set(i: Int, e: Double, c: Double, k: Long, s: Long) {
        estimates[i] = e
        costs[i] = c
        keys[i] = k
        seqs[i] = s
    }

    private fun grow() {
        val n = keys.size * 2
        estimates = estimates.copyOf(n)
        costs = costs.copyOf(n)
        keys = keys.copyOf(n)
        seqs = seqs.copyOf(n)
    }
}
