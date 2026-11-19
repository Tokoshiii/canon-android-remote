package dev.canonremote.core.remote

import kotlinx.serialization.json.Json

object MessageCodec {
    const val PROTOCOL = 1

    private val json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
    }

    fun encode(m: Message): String = json.encodeToString(Message.serializer(), m)

    fun decode(text: String): Message = try {
        json.decodeFromString(Message.serializer(), text)
    } catch (e: Exception) {
        throw ProtocolException("Invalid message: ${e.message}", e)
    }
}
