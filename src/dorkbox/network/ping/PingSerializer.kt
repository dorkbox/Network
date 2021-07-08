/*
 * Copyright 2020 dorkbox, llc
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
package dorkbox.network.ping

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output

class PingSerializer: Serializer<Ping>() {
    override fun write(kryo: Kryo, output: Output, ping: Ping) {
        output.writeInt(ping.packedId)
        output.writeLong(ping.pingTime)
        output.writeLong(ping.pongTime)
    }

    override fun read(kryo: Kryo, input: Input, type: Class<out Ping>): Ping {
        val ping = Ping()
        ping.packedId = input.readInt()
        ping.pingTime = input.readLong()
        ping.pongTime = input.readLong()
        return ping
    }
}
