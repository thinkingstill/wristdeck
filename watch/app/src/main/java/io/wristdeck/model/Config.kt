package io.wristdeck.model

data class Config(
    val host: String,
    val port: Int,
    val pin: String,
    val keepAlive: Boolean,
) {
    companion object {
        const val DEFAULT_PORT = 8787
    }
}
