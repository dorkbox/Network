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

package dorkbox.network.serialization

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import dorkbox.network.connection.DisconnectMessage

internal class DisconnectSerializer: Serializer<DisconnectMessage>() {
    override fun write(kryo: Kryo, output: Output, `object`: DisconnectMessage) {
        output.writeBoolean(`object`.closeEverything)
    }

    override fun read(kryo: Kryo, input: Input, type: Class<out DisconnectMessage>): DisconnectMessage {
        val closeEverything = input.readBoolean()
        return if (closeEverything) {
            DisconnectMessage.CLOSE_EVERYTHING
        } else {
            DisconnectMessage.CLOSE_FOR_SESSION
        }
    }
}
