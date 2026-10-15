package app.route

import app.support.FleetTest
import app.support.fleetTest
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import protocol.api.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun noDig(
    minX: Int = 0, maxX: Int = 4, minZ: Int = 0, maxZ: Int = 4, minY: Int? = null, maxY: Int? = null,
    name: String = "Base"
) = ZoneRequest(
    name = name, type = ZoneTypes.NO_DIG, minX = minX, maxX = maxX, minZ = minZ, maxZ = maxZ, minY = minY, maxY = maxY
)

private suspend fun FleetTest.create(request: ZoneRequest): HttpResponse =
    client.post("/api/zones") {
        contentType(ContentType.Application.Json)
        setBody(request)
    }

private suspend fun FleetTest.update(id: Long, request: ZoneRequest): HttpResponse =
    client.put("/api/zones/$id") {
        contentType(ContentType.Application.Json)
        setBody(request)
    }

private suspend fun FleetTest.created(request: ZoneRequest): Zone {
    val response = create(request)
    assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
    return response.body()
}

private suspend fun HttpResponse.error(): String = body<ApiError>().error

class ZoneRoutesTest {

    // --- creating and reading ---

    @Test
    fun `a created zone is returned with an id and can be read back`() = fleetTest {
        val zone = created(
            ZoneRequest(
                name = "Village", type = ZoneTypes.NO_DIG, minX = -10, maxX = 20, minZ = 5, maxZ = 40,
                minY = 50, maxY = 90, world = "w", dimension = "overworld"
            )
        )

        assertEquals("Village", zone.name)
        assertEquals(ZoneTypes.NO_DIG, zone.type)
        assertEquals(listOf(-10, 20, 5, 40), listOf(zone.minX, zone.maxX, zone.minZ, zone.maxZ))
        assertEquals(50 to 90, zone.minY to zone.maxY)
        assertEquals("w", zone.world)
        assertEquals("overworld", zone.dimension)

        assertEquals(zone, client.get("/api/zones/${zone.zoneId}").body<Zone>())
    }

    @Test
    fun `Y bounds are optional, and stay null`() = fleetTest {
        val open = created(noDig())
        val floorOnly = created(noDig(minY = 12, name = "Floor"))

        assertNull(open.minY)
        assertNull(open.maxY)
        assertEquals(12 to null, floorOnly.minY to floorOnly.maxY)
        assertEquals(floorOnly, client.get("/api/zones/${floorOnly.zoneId}").body<Zone>())
    }

    @Test
    fun `all zones are listed, and can be filtered by type`() = fleetTest {
        val base = created(noDig())
        val quarry = created(noDig(name = "Pit").copy(type = ZoneTypes.QUARRY))

        assertEquals(listOf(base, quarry), client.get("/api/zones").body<List<Zone>>())
        assertEquals(listOf(quarry), client.get("/api/zones?type=quarry").body<List<Zone>>())
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/zones?type=farm").status)
    }

    @Test
    fun `an unknown zone is not found`() = fleetTest {
        assertEquals(HttpStatusCode.NotFound, client.get("/api/zones/12345").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/zones/abc").status)
    }

    @Test
    fun `zones may overlap`() = fleetTest {
        created(noDig())

        assertEquals(HttpStatusCode.Created, create(noDig(name = "Again")).status)
    }

    // --- validation ---

    @Test
    fun `a zone needs a known type`() = fleetTest {
        val response = create(noDig().copy(type = "farm"))

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue("type must be one of" in response.error(), response.error())
    }

    @Test
    fun `a minimum can't be more than its maximum`() = fleetTest {
        assertTrue("minX" in create(noDig(minX = 5, maxX = 4)).error())
        assertTrue("minZ" in create(noDig(minZ = 5, maxZ = 4)).error())
        assertTrue("minY" in create(noDig(minY = 70, maxY = 60)).error())
        assertEquals(HttpStatusCode.Created, create(noDig(minX = 4, maxX = 4, minY = 60, maxY = 60)).status)
    }

    @Test
    fun `a zone needs a name of at most 32 printable characters, trimmed`() = fleetTest {
        assertEquals(HttpStatusCode.BadRequest, create(noDig(name = "  ")).status)
        assertEquals(HttpStatusCode.BadRequest, create(noDig(name = "x".repeat(MAX_ZONE_NAME_LENGTH + 1))).status)
        assertEquals(HttpStatusCode.BadRequest, create(noDig(name = "tab\there")).status)
        assertEquals("Base", created(noDig(name = "  Base ")).name)
    }

    @Test
    fun `a malformed body is refused`() = fleetTest {
        val response = client.post("/api/zones") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Base"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // --- updating and deleting ---

    @Test
    fun `a zone can be replaced, keeping its id`() = fleetTest {
        val zone = created(noDig())

        val response = update(zone.zoneId, noDig(minX = -3, maxY = 80, name = "Moved").copy(type = ZoneTypes.QUARRY))

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val replaced = response.body<Zone>()
        assertEquals(zone.zoneId, replaced.zoneId)
        assertEquals("Moved", replaced.name)
        assertEquals(ZoneTypes.QUARRY, replaced.type)
        assertEquals(-3, replaced.minX)
        assertEquals(null to 80, replaced.minY to replaced.maxY)
        assertEquals(listOf(replaced), client.get("/api/zones").body<List<Zone>>())
    }

    @Test
    fun `replacing checks the request, and an unknown zone`() = fleetTest {
        val zone = created(noDig())

        assertEquals(HttpStatusCode.BadRequest, update(zone.zoneId, noDig(minX = 9, maxX = 1)).status)
        assertEquals(HttpStatusCode.NotFound, update(12345, noDig()).status)
    }

    @Test
    fun `a zone can be deleted`() = fleetTest {
        val zone = created(noDig())

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/zones/${zone.zoneId}").status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/zones/${zone.zoneId}").status)
        assertEquals(emptyList(), client.get("/api/zones").body<List<Zone>>())
    }
}
