package protocol

import kotlinx.serialization.json.Json

val ProtocolJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
