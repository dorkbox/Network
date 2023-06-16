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
import kotlinx.atomicfu.atomic
import org.junit.Assert
import org.junit.Test

class PingTest : BaseTest() {
    val counter = atomic(0)
    @Test
    fun RmiPing() {
        val clientSuccess = atomic(false)

        run {
            val configuration = serverConfig()

            val server: Server<Connection> = Server(configuration)
            addEndPoint(server)
            server.bind()
        }

        run {
            val config = clientConfig()

            val client: Client<Connection> = Client(config)
            addEndPoint(client)

            client.onConnect {
                repeat(100) {
                    ping {
                        // a ping object is returned, once the round-trip is complete
                        val count = counter.getAndIncrement()
                        if (count == 99) {
                            clientSuccess.value = true

                            logger.error("out-bound: $outbound")
                            logger.error("in-bound: $inbound")
                            logger.error("round-trip: $roundtrip")

                            stopEndPoints()
                        }
                    }
                }
            }

            client.connect(LOCALHOST)
        }

        waitForThreads()

        Assert.assertTrue(clientSuccess.value)
    }
}
