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
        const val TURTLE_POSITION = "/turtles/{id}/position"
        const val TURTLE_REGISTER = "/turtles/{id}/register"
        const val TURTLE_HEARTBEAT = "/turtles/{id}/heartbeat"
        const val TURTLE_SEED = "/turtles/{id}/seed"
    }
}
