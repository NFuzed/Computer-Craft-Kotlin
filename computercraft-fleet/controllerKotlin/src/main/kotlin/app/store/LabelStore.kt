package app.store

import protocol.api.PendingLabel
import java.util.concurrent.ConcurrentHashMap

/**
 * Renames waiting to be picked up by a turtle, at most one per turtle (a newer rename replaces an older one).
 * Kept in memory and written through to the `pending_label` table, so a rename survives a server restart before the
 * turtle collects it.
 */
class LabelStore(private val db: Database) {
    private val map = ConcurrentHashMap<Int, PendingLabel>()

    init {
        load()
    }

    @Synchronized
    fun put(turtleId: Int, pending: PendingLabel) {
        db.transaction {
            it.execute(
                """
                INSERT INTO pending_label (turtle_id, label, ts) VALUES (?, ?, ?)
                ON CONFLICT (turtle_id) DO UPDATE SET label = EXCLUDED.label, ts = EXCLUDED.ts
                """,
                turtleId, pending.label, pending.ts
            )
        }
        map[turtleId] = pending
    }

    fun get(turtleId: Int): PendingLabel? = map[turtleId]

    fun ids(): Set<Int> = map.keys

    /** Removes the turtle's rename only if it is for [label], so a turtle still using an old label can't clear it. */
    @Synchronized
    fun acknowledge(turtleId: Int, label: String?): Boolean {
        val current = map[turtleId] ?: return false
        if (current.label != label) return false
        db.transaction { it.execute("DELETE FROM pending_label WHERE turtle_id = ?", turtleId) }
        map.remove(turtleId)
        return true
    }

    private fun load() {
        db.transaction { conn ->
            conn.query("SELECT * FROM pending_label") { rs ->
                rs.getInt("turtle_id") to PendingLabel(label = rs.getString("label"), ts = rs.getLong("ts"))
            }.toMap(map)
        }
    }
}
