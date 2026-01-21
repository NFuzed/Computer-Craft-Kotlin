package protocol

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

val ProtocolJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

inline fun <reified T> decode(text: String): T = ProtocolJson.decodeFromString(text)
inline fun <reified T> encode(value: T): String = ProtocolJson.encodeToString(value)
