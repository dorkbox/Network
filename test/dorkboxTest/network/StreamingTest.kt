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

package dorkboxTest.network

import dorkbox.bytes.sha256
import dorkbox.network.Client
import dorkbox.network.Server
import dorkbox.network.connection.Connection
import dorkbox.util.Sys
import org.agrona.ExpandableDirectByteBuffer
import org.junit.Assert
import org.junit.Test
import java.io.File
import java.rmi.server.RemoteObject
import java.security.SecureRandom

class StreamingTest : BaseTest() {

    @Test
    fun sendStreamingObject() {
        //  if this number is too high, we will run out of memory
        // ExpandableDirectByteBuffer.MAX_BUFFER_LENGTH = 1073741824
        val sizeToTest = ExpandableDirectByteBuffer.MAX_BUFFER_LENGTH / 32
        val hugeData = ByteArray(sizeToTest)
        SecureRandom().nextBytes(hugeData)


        val server = run {
            val configuration = serverConfig()

            val server: Server<Connection> = Server(configuration)
            addEndPoint(server)

            server.onMessage<ByteArray> {
                logger.error { "received data, shutting down!" }
                Assert.assertEquals(sizeToTest, it.size)
                Assert.assertArrayEquals(hugeData, it)
                stopEndPoints()
            }
            server
        }

        val client = run {
            val config = clientConfig()

            val client: Client<Connection> = Client(config) {
                Connection(it)
            }
            addEndPoint(client)

            client.onConnect {
                logger.error { "Sending huge data: ${Sys.getSizePretty(hugeData.size)} bytes" }
                send(hugeData)
                logger.error { "Done sending huge data: ${hugeData.size} bytes" }
            }

            client
        }

        server.bind(2000)
        client.connect(LOCALHOST, 2000)


        waitForThreads()
    }

    @Test
    fun sendRmiStreamingObject() {
        //  if this number is too high, we will run out of memory
        // ExpandableDirectByteBuffer.MAX_BUFFER_LENGTH = 1073741824
        val sizeToTest = ExpandableDirectByteBuffer.MAX_BUFFER_LENGTH / 32
        val hugeData = ByteArray(sizeToTest)
        SecureRandom().nextBytes(hugeData)


        val server = run {
            val configuration = serverConfig()
            configuration.serialization.rmi.register(TestStream::class.java, TestStreamCow::class.java)

            val server: Server<Connection> = Server(configuration)
            addEndPoint(server)

            server.onInit {
                rmi.save(TestStreamCow(this@StreamingTest, hugeData), 765)
            }

            server
        }

        val client = run {
            val config = clientConfig()

            val client: Client<Connection> = Client(config) {
                Connection(it)
            }
            addEndPoint(client)

            client.onConnect {
                logger.error { "Sending huge data: ${Sys.getSizePretty(hugeData.size)} bytes" }
                val remote = rmi.get<TestStream>(765)
                dorkbox.network.rmi.RemoteObject.cast(remote).async = true
                remote.send(hugeData)
                logger.error { "Done sending huge data: ${hugeData.size} bytes" }
            }

            client
        }

        server.bind(2000)
        client.connect(LOCALHOST, 2000)


        waitForThreads()
    }

    @Test
    fun sendRmiFile() {
        val file = File("LICENSE")

        val server = run {
            val configuration = serverConfig()
            configuration.serialization.rmi.register(TestStream::class.java, TestStreamCow::class.java)

            val server: Server<Connection> = Server(configuration)
            addEndPoint(server)

            server.onInit {
                rmi.save(TestStreamCow(this@StreamingTest, null, file), 765)
            }

            server
        }

        val client = run {
            val config = clientConfig()

            val client: Client<Connection> = Client(config) {
                Connection(it)
            }
            addEndPoint(client)

            client.onConnect {
                logger.error { "Sending file: $file" }
                val remote = rmi.get<TestStream>(765)
                remote.send(file)
                logger.error { "Done sending file: $file" }
            }

            client
        }

        server.bind(2000)
        client.connect(LOCALHOST, 2000)


        waitForThreads()
    }

}

interface TestStream {
    fun send(byteArray: ByteArray)
    fun send(file: File)
}

class TestStreamCow(val unitTest: BaseTest, val hugeData: ByteArray? = null, val file: File? = null) : TestStream {
    override fun send(byteArray: ByteArray) {
        // not used
    }

    override fun send(file: File) {
        // not used
    }

    fun send(connection: Connection, byteArray: ByteArray) {
        connection.logger.error { "received data, shutting down!" }
        Assert.assertEquals(hugeData!!.size, byteArray.size)
        Assert.assertArrayEquals(hugeData, byteArray)
        unitTest.stopEndPoints()
    }

    fun send(connection: Connection, file: File) {
        connection.logger.error { "received data, shutting down!" }
        connection.logger.error { "FILE: $file" }
        Assert.assertArrayEquals(this.file!!.sha256(), file.sha256())
        file.delete()
        unitTest.stopEndPoints()
    }
}
