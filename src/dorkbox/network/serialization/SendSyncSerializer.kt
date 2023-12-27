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
import dorkbox.network.connection.SendSync

internal class SendSyncSerializer: Serializer<SendSync>() {
    override fun write(kryo: Kryo, output: Output, ssync: SendSync) {
        output.writeInt(ssync.id)
        kryo.writeClassAndObject(output, ssync.message)
    }

    override fun read(kryo: Kryo, input: Input, type: Class<out SendSync>): SendSync {
        val ssync = SendSync()
        ssync.id = input.readInt()
        ssync.message = kryo.readClassAndObject(input)

        return ssync
    }
}
