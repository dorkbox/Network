package dorkbox.network.connection.streaming

class StreamingData(var streamId: Long) : StreamingMessage {

    // These are set just after we receive the message, and before we process it
    @Transient var payload: ByteArray? = null
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as StreamingData

        if (streamId != other.streamId) return false
        if (payload != null) {
            if (other.payload == null) return false
            if (!payload.contentEquals(other.payload)) return false
        } else if (other.payload != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = streamId.hashCode()
        result = 31 * result + (payload?.contentHashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "StreamingData(streamId=$streamId)"
    }
}
