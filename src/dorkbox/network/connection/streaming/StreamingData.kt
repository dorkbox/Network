/*
 * Copyright 2023 dorkbox, llc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dorkbox.network.connection.streaming

import dorkbox.bytes.xxHash32

class StreamingData(val streamId: Int) : StreamingMessage {

    var payload: ByteArray? = null
    var startPosition: Int = 0
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as StreamingData

        if (streamId != other.streamId) return false
        if (payload != null) {
            if (other.payload == null) return false
            if (!payload.contentEquals(other.payload)) return false
        } else if (other.payload != null) return false

        if (startPosition != other.startPosition) return false

        return true
    }

    override fun hashCode(): Int {
        var result = streamId.hashCode()
        result = 31 * result + (payload?.contentHashCode() ?: 0)
        result = 31 * result + (startPosition)
        return result
    }

    override fun toString(): String {
        return "StreamingData(streamId=$streamId position=${startPosition}, xxHash=${payload?.xxHash32()})"
    }
}
