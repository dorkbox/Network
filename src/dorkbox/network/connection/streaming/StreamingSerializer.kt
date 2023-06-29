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

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output

class StreamingControlSerializer: Serializer<StreamingControl>() {
    override fun write(kryo: Kryo, output: Output, data: StreamingControl) {
        output.writeByte(data.state.ordinal)
        output.writeVarInt(data.streamId, true)
        output.writeVarLong(data.totalSize, true)
    }

    override fun read(kryo: Kryo, input: Input, type: Class<out StreamingControl>): StreamingControl {
        val stateOrdinal = input.readByte().toInt()
        val state = StreamingState.values().first { it.ordinal == stateOrdinal }
        val streamId = input.readVarInt(true)
        val totalSize = input.readVarLong(true)

        return StreamingControl(state, streamId, totalSize)
    }
}

class StreamingDataSerializer: Serializer<StreamingData>() {
    override fun write(kryo: Kryo, output: Output, data: StreamingData) {
        output.writeVarInt(data.streamId, true)
    }

    override fun read(kryo: Kryo, input: Input, type: Class<out StreamingData>): StreamingData {
        val streamId = input.readVarInt(true)
        return StreamingData(streamId)
    }
}
