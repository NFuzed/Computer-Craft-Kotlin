package app.service

import io.ktor.websocket.*
import java.util.concurrent.ConcurrentHashMap

/** The open /ws/turtle connection of each online turtle, at most one per turtle. */
class TurtleSessions {
    private val map = ConcurrentHashMap<Int, WebSocketSession>()

    /** Makes [session] the turtle's connection and returns the one it replaced, if any. */
    fun open(turtleId: Int, session: WebSocketSession): WebSocketSession? = map.put(turtleId, session)

    /** Forgets [session], unless the turtle has already reconnected with a newer one. */
    fun close(turtleId: Int, session: WebSocketSession) {
        map.remove(turtleId, session)
    }

    fun isConnected(turtleId: Int): Boolean = map.containsKey(turtleId)

    /** Sends [text] to the turtle. Returns false if it isn't connected or the send failed. */
    suspend fun send(turtleId: Int, text: String): Boolean {
        val session = map[turtleId] ?: return false
        return runCatching { session.send(Frame.Text(text)) }.isSuccess
    }
}
