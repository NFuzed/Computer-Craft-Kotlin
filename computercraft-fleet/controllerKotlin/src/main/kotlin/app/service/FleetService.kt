package app.service

import app.store.*
import io.ktor.websocket.*
import kotlinx.serialization.encodeToString
import protocol.ProtocolJson
import protocol.api.*
import protocol.messages.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Combines the registry (labels, last seen), sessions (who is connected), position store (where they are), seed store
 * (positions waiting to be handed to turtles) and label store (pending renames) into the [TurtleStatus] view served by
 * the API, keeps each turtle's last reported inventory, equipment, refuel and command, adds the blocks turtles see to
 * the world map, and pushes seeds, renames, slot selections, refuels and commands to connected turtles.
 */
class FleetService(
    private val registry: TurtleRegistry,
    private val sessions: TurtleSessions,
    private val positions: PositionStore,
    private val seeds: SeedStore,
    private val labels: LabelStore,
    private val inventories: InventoryStore,
    private val equipments: EquipmentStore,
    private val worldMap: WorldMapStore,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 }
) {
    // Starts at the current time in ms so ids stay unique across server restarts
    private val nextSeedId = AtomicLong(System.currentTimeMillis())
    private val nextRefuelId = AtomicLong(System.currentTimeMillis())
    private val nextCommandId = AtomicLong(System.currentTimeMillis())

    // Only each turtle's latest refuel and command, in memory: they're feedback for whoever just asked, not history
    private val lastRefuels = ConcurrentHashMap<Int, RefuelResult>()
    private val lastCommands = ConcurrentHashMap<Int, CommandResult>()

    fun allStatuses(): List<TurtleStatus> {
        val ids = registry.all().map { it.turtleId } + positions.ids() + seeds.ids() + labels.ids()
        return ids.distinct().sorted().mapNotNull { status(it) }
    }

    fun status(turtleId: Int): TurtleStatus? {
        val info = registry.get(turtleId)
        val pos = positions.get(turtleId)
        val seed = seeds.get(turtleId)
        val pendingLabel = labels.get(turtleId)
        if (info == null && pos == null && seed == null && pendingLabel == null) return null

        return TurtleStatus(
            turtleId = turtleId,
            label = info?.label,
            online = sessions.isConnected(turtleId),
            lastSeen = info?.lastSeen,
            position = pos,
            pendingSeed = seed,
            pendingLabel = pendingLabel,
            equipment = equipments.get(turtleId),
            lastCommand = lastCommands[turtleId]
        )
    }

    /**
     * Called when a turtle says hello on a new connection. Replaces any older connection it still has open
     * (e.g. it rebooted before the server noticed), then sends whatever is waiting for it.
     */
    suspend fun connect(hello: Hello, session: WebSocketSession) {
        val turtleId = hello.turtleId
        updateLabel(turtleId, hello.label?.takeIf { it.isNotBlank() })
        sessions.open(turtleId, session)?.close(CloseReason(CloseReason.Codes.NORMAL, "Replaced by a new connection"))

        labels.get(turtleId)?.let { sessions.send(turtleId, encode(RenameMessage(label = it.label))) }
        seeds.get(turtleId)?.let { sessions.send(turtleId, encode(SeedMessage(seed = it))) }
    }

    fun disconnect(turtleId: Int, session: WebSocketSession) {
        sessions.close(turtleId, session)
        registry.recordSeen(turtleId)
    }

    /** A turtle reports the label it now has, after applying a rename. */
    fun labelChanged(update: LabelUpdate) {
        updateLabel(update.turtleId, update.label?.takeIf { it.isNotBlank() })
    }

    /**
     * Queues a position for the turtle to adopt, and sends it right away if the turtle is connected. The turtle
     * doesn't need to be registered yet, so a seed can be set before the turtle is first turned on.
     */
    suspend fun seed(turtleId: Int, request: SeedRequest): PositionSeed {
        val seed = PositionSeed(
            seedId = nextSeedId.incrementAndGet(),
            x = request.x,
            y = request.y,
            z = request.z,
            heading = request.heading,
            world = request.world,
            dimension = request.dimension,
            source = request.source,
            ts = clock()
        )
        seeds.put(turtleId, seed)
        sessions.send(turtleId, encode(SeedMessage(seed = seed)))
        return seed
    }

    /**
     * Queues a rename for the turtle (null clears its label), and sends it right away if the turtle is connected.
     * Like seeds, the turtle doesn't need to be registered yet.
     */
    suspend fun rename(turtleId: Int, label: String?): TurtleStatus {
        labels.put(turtleId, PendingLabel(label = label, ts = clock()))
        sessions.send(turtleId, encode(RenameMessage(label = label)))
        return status(turtleId)!!
    }

    fun inventory(turtleId: Int): TurtleInventory? =
        inventories.get(turtleId)?.copy(lastRefuel = lastRefuels[turtleId])

    /** A turtle reports its whole inventory, replacing what was stored. */
    fun recordInventory(update: InventoryUpdate) {
        registry.recordSeen(update.turtleId)
        inventories.put(
            update.turtleId,
            TurtleInventory(
                selectedSlot = update.selectedSlot,
                slots = update.slots,
                fuel = update.fuel,
                ts = update.ts
            )
        )
    }

    /** A turtle reports its upgrades, replacing what was stored. */
    fun recordEquipment(update: EquipmentUpdate) {
        registry.recordSeen(update.turtleId)
        equipments.put(
            update.turtleId,
            TurtleEquipment(
                left = update.left,
                right = update.right,
                itemsKnown = update.itemsKnown,
                capabilities = capabilitiesOf(update.left, update.right),
                ts = update.ts
            )
        )
    }

    /**
     * Tells the turtle to burn [count] items from its selected slot (null = the whole stack). Not queued, for the same
     * reason as [selectSlot]. Returns null if the turtle isn't connected.
     */
    suspend fun refuel(turtleId: Int, count: Int?): RefuelCommand? {
        val command = RefuelCommand(refuelId = nextRefuelId.incrementAndGet(), count = count, ts = clock())
        val sent = sessions.send(turtleId, encode(RefuelMessage(refuelId = command.refuelId, count = count)))
        return command.takeIf { sent }
    }

    /** A turtle reports how a refuel went. */
    fun recordRefuelResult(update: RefuelResultUpdate) {
        registry.recordSeen(update.turtleId)
        lastRefuels[update.turtleId] = RefuelResult(
            refuelId = update.refuelId,
            refueled = update.refueled,
            error = update.error,
            fuelAdded = update.fuelAdded,
            ts = update.ts
        )
    }

    /**
     * Tells the turtle to run [command] (one of [Commands]). Not queued, for the same reason as [selectSlot]: a move
     * picked while the turtle is offline could be stale by the time it connects. Returns null if the turtle isn't
     * connected.
     */
    suspend fun sendCommand(turtleId: Int, command: String): TurtleCommand? {
        val sent = TurtleCommand(commandId = nextCommandId.incrementAndGet(), command = command, ts = clock())
        val delivered = sessions.send(turtleId, encode(CommandMessage(commandId = sent.commandId, command = command)))
        return sent.takeIf { delivered }
    }

    /** A turtle reports how a command went. */
    fun recordCommandResult(update: CommandResultUpdate) {
        registry.recordSeen(update.turtleId)
        lastCommands[update.turtleId] = CommandResult(
            commandId = update.commandId,
            command = update.command,
            success = update.success,
            error = update.error,
            ts = update.ts
        )
    }

    /**
     * Tells the turtle to select [slot]. Unlike seeds and renames this isn't queued, since a slot picked while the
     * turtle is offline would be stale by the time it connects. Returns false if the turtle isn't connected.
     */
    suspend fun selectSlot(turtleId: Int, slot: Int): Boolean =
        sessions.send(turtleId, encode(SelectSlotMessage(slot = slot)))

    fun recordPosition(update: PositionUpdate) {
        registry.recordSeen(update.turtleId)
        update.seedId?.let { seeds.acknowledge(update.turtleId, it) }
        // A turtle can't travel between worlds or dimensions, so a new one means it was re-seeded with the right one:
        // the blocks it reported under the old map were really in the new one. Moved before the position is saved, so
        // if this fails the next update tries again.
        positions.get(update.turtleId)?.let { previous ->
            worldMap.moveTurtleBlocks(
                update.turtleId,
                from = MapId(previous.world, previous.dimension),
                to = MapId(update.world, update.dimension)
            )
        }
        positions.upsert(
            update.turtleId,
            TurtlePosition(
                x = update.x,
                y = update.y,
                z = update.z,
                heading = update.heading,
                world = update.world,
                dimension = update.dimension,
                ts = update.ts
            )
        )
    }

    /** A turtle reports the blocks around it; they replace what the world map knew about those cells. */
    fun recordBlocks(update: BlockObservations) {
        registry.recordSeen(update.turtleId)
        worldMap.record(
            MapId(update.world, update.dimension),
            update.blocks.map {
                KnownBlock(x = it.x, y = it.y, z = it.z, name = it.name, turtleId = update.turtleId, ts = update.ts)
            }
        )
    }

    /** The known blocks in the box from [min] to [max], both inclusive, leaving out known air unless [includeAir]. */
    fun mapRegion(id: MapId, min: BlockPos, max: BlockPos, includeAir: Boolean = true): MapRegion =
        MapRegion(world = id.world, dimension = id.dimension, blocks = worldMap.region(id, min, max, includeAir))

    private fun updateLabel(turtleId: Int, label: String?) {
        registry.setLabel(turtleId, label)
        labels.acknowledge(turtleId, label)
    }

    private inline fun <reified T> encode(message: T): String = ProtocolJson.encodeToString(message)
}
