package dorkbox.network.connection.streaming

data class StreamingControl(val state: StreamingState, val streamId: Long,
                            val totalSize: Long = 0L,
                            val isFile: Boolean = false, val fileName: String = ""): StreamingMessage
