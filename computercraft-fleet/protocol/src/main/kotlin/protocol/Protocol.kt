package protocol

object Protocol {
    const val VERSION = 1

    object Paths {
        const val MANIFEST = "/manifest.json"
        const val FILES = "/files/{path...}"
        const val BOOT = "/bootstrap/boot.lua"

        const val API = "/api"
        const val TURTLES = "/turtles"
        const val TURTLE = "/turtles/{id}"
        const val TURTLE_SEED = "/turtles/{id}/seed"
        const val TURTLE_LABEL = "/turtles/{id}/label"
        const val TURTLE_INVENTORY = "/turtles/{id}/inventory"
        const val TURTLE_SELECTED_SLOT = "/turtles/{id}/inventory/selected"
        const val TURTLE_REFUEL = "/turtles/{id}/refuel"
        const val TURTLE_COMMANDS = "/turtles/{id}/commands"

        const val WS_TURTLE = "/ws/turtle"
    }

    /** `type` values of the messages sent over [Paths.WS_TURTLE]. */
    object MessageTypes {
        // turtle → server
        const val HELLO = "hello"
        const val POSITION_UPDATE = "position_update"
        const val LABEL_UPDATE = "label_update"
        const val INVENTORY_UPDATE = "inventory_update"
        const val REFUEL_RESULT = "refuel_result"
        const val EQUIPMENT_UPDATE = "equipment_update"
        const val COMMAND_RESULT = "command_result"

        // server → turtle
        const val SEED = "seed"
        const val RENAME = "rename"
        const val SELECT_SLOT = "select_slot"
        const val REFUEL = "refuel"
        const val COMMAND = "command"
        const val ERROR = "error"
    }
}
