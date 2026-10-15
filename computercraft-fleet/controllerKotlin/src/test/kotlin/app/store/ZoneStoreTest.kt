package app.store

import app.support.withDatabase
import protocol.api.Zone
import protocol.api.ZoneTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun zone(name: String = "Base", minY: Int? = null, maxY: Int? = null) = Zone(
    zoneId = 0, // assigned by the store
    name = name, type = ZoneTypes.NO_DIG, minX = -4, maxX = 4, minZ = 0, maxZ = 9, minY = minY, maxY = maxY,
    world = "w", dimension = "overworld", ts = 100
)

class ZoneStoreTest {

    @Test
    fun `zones survive a restart, open Y bounds included`() = withDatabase { db ->
        val open = ZoneStore(db).insert(zone("Open"))
        val bounded = ZoneStore(db).insert(zone("Bounded", minY = -20, maxY = 70))
        val replaced = ZoneStore(db).insert(zone("Before"))
        val deleted = ZoneStore(db).insert(zone("Deleted"))
        ZoneStore(db).replace(replaced.copy(name = "After", minY = 12))
        ZoneStore(db).delete(deleted.zoneId)

        val reloaded = ZoneStore(db)

        assertEquals(listOf(open, bounded, replaced.copy(name = "After", minY = 12)), reloaded.all())
        assertNull(reloaded.get(open.zoneId)?.minY)
        assertNull(reloaded.get(deleted.zoneId))
    }
}
