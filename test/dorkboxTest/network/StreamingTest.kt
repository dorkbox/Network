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

import dorkbox.network.Client
import dorkbox.network.Server
import dorkbox.network.connection.Connection
import org.agrona.ExpandableDirectByteBuffer
import org.junit.Assert
import org.junit.Test
import java.security.SecureRandom

class StreamingTest : BaseTest() {

    @Test
    fun sendStreamingObject() {
        val sizeToTest = ExpandableDirectByteBuffer.MAX_BUFFER_LENGTH / 8
        val hugeData = ByteArray(sizeToTest)
        SecureRandom().nextBytes(hugeData)


        run {
            val configuration = serverConfig()

            val server: Server<Connection> = Server(configuration)
            addEndPoint(server)
            server.bind()

            server.onMessage<ByteArray> {
                println("received data, shutting down!")
                Assert.assertEquals(sizeToTest, it.size)
                Assert.assertArrayEquals(hugeData, it)
                stopEndPoints()
            }
        }

        run {
            val config = clientConfig()

            val client: Client<Connection> = Client(config) {
                Connection(it)
            }
            addEndPoint(client)

            client.onConnect {
                logger.error { "Sending huge data: ${hugeData.size} bytes" }
                send(hugeData)
            }

            client.connect(LOCALHOST)
        }

        waitForThreads()
    }
}
