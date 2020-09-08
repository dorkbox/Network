package dorkbox.network.aeron

internal interface AeronPoller {
    fun poll(): Int
    fun close()
    fun serverInfo(): String
}

