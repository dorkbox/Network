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
import dorkbox.network.connection.Connection
import dorkbox.network.connection.CryptoManagement
import dorkbox.network.connection.EndPoint
import dorkbox.network.connection.streaming.StreamingManager
import java.io.File

internal class FileContentsSerializer<CONNECTION : Connection> : Serializer<File>() {
    lateinit var streamingManager: StreamingManager<CONNECTION>


    init {
        isImmutable = true
    }

    @Suppress("UNCHECKED_CAST")
    override fun write(kryo: Kryo, output: Output, file: File) {
        val kryoExtra = kryo as KryoWriter<CONNECTION>
        val connection = kryoExtra.connection
        val publication = connection.publication
        val endPoint = connection.endPoint as EndPoint<CONNECTION>
        val sendIdleStrategy = connection.sendIdleStrategy

        // NOTE: the stream session ID is a combination of the connection ID + random ID (on the receiving side)
        val streamSessionId = CryptoManagement.secureRandom.nextInt()

        // use the streaming manager to send the file in blocks to the remove endpoint
        endPoint.serialization.withKryo {
            streamingManager.sendFile(
                file = file,
                publication = publication,
                endPoint = endPoint,
                kryo = this,
                sendIdleStrategy = sendIdleStrategy,
                connection = connection,
                streamSessionId = streamSessionId
            )
        }

//        output.writeString(file.path)
        output.writeInt(streamSessionId, true)
    }

    @Suppress("UNCHECKED_CAST")
    override fun read(kryo: Kryo, input: Input, type: Class<out File>): File {
        val kryoExtra = kryo as KryoReader<CONNECTION>
        val connection = kryoExtra.connection
        val endPoint = connection.endPoint as EndPoint<CONNECTION>


//        val path = input.readString()
        val streamSessionId = input.readInt(true)

        // get the file object out of the streaming manager!!!
        val file = streamingManager.getFile(connection, endPoint, streamSessionId)

        return file
    }
}
