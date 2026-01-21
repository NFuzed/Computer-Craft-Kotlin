package protocol

object Protocol {
    const val VERSION = 1

    object Paths {
        const val WS = "/ws"
        const val MANIFEST = "/manifest.json"
        const val FILES = "/files/{path...}"
        const val BOOT = "/bootstrap/boot.lua"
    }
}
