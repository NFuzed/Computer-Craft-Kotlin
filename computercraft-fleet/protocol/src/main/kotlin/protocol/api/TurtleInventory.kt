package protocol.api

import kotlinx.serialization.Serializable

/** Number of inventory slots a turtle has, numbered 1 to 16. */
const val INVENTORY_SIZE = 16

/** One non-empty inventory slot, as reported by turtle.getItemDetail. */
@Serializable
data class InventorySlot(
    val slot: Int,                  // 1..16
    val name: String,               // item id, e.g. "minecraft:cobblestone"
    val count: Int,
    val nbt: String? = null         // hash of the item's NBT, if it has any
)

/** The last inventory a turtle reported. Empty slots are left out of [slots]. */
@Serializable
data class TurtleInventory(
    val selectedSlot: Int,
    val slots: List<InventorySlot>,
    val ts: Long
)

/** Sent by the dashboard to change which slot a turtle has selected. */
@Serializable
data class SelectSlotRequest(
    val slot: Int                   // 1..16
)
