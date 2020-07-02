package dorkbox.network.connection

enum class MediaDriverType(private val type: String) {
    IPC("ipc"), UDP("udp");

    override fun toString(): String {
        return type
    }
}
