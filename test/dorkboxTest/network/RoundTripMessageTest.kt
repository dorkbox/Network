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
import dorkbox.network.ping.Ping
import dorkbox.network.rmi.RmiUtils
import kotlinx.atomicfu.atomic
import org.junit.Assert
import org.junit.Test

class RoundTripMessageTest : BaseTest() {
    @Test
    fun MessagePing() {
        // session/stream count errors
        val serverSuccess = atomic(false)
        val clientSuccess = atomic(false)

        val server = run {
            val configuration = serverConfig()
            configuration.serialization.register(PingMessage::class.java)

            val server: Server<Connection> = Server(configuration)
            addEndPoint(server)

            server.onMessage<PingMessage> { ping ->
                serverSuccess.value = true

                ping.pongTime = System.currentTimeMillis()
                send(ping)
            }

            server
        }

        val client = run {
            val config = clientConfig()

            val client: Client<Connection> = Client(config)
            addEndPoint(client)

            client.onMessage<PingMessage> { ping ->
                clientSuccess.value = true

                ping.finishedTime = System.currentTimeMillis()

                logger.error("out-bound: ${ping.outbound}")
                logger.error("in-bound: ${ping.inbound}")
                logger.error("round-trip: ${ping.roundtrip}")

                stopEndPoints()
            }

            client.onConnect {
                logger.error("Connecting...")

                val ping = PingMessage()
                ping.packedId = 1
                ping.pingTime = System.currentTimeMillis()

                send(ping)
            }

            client
        }

        server.bind(2000)
        client.connect(LOCALHOST, 2000)

        waitForThreads()

        Assert.assertTrue(serverSuccess.value)
        Assert.assertTrue(clientSuccess.value)
    }

    class PingMessage {
        var packedId = 0
        var pingTime = 0L
        var pongTime = 0L

        @Transient
        var finishedTime = 0L

        /**
         * The time it took for the remote connection to return the ping to us. This is only accurate if the clocks are synchronized
         */
        val inbound: Long
            get() {
                return finishedTime - pongTime
            }

        /**
         * The time it took for us to ping the remote connection. This is only accurate if the clocks are synchronized.
         */
        val outbound: Long
            get() {
                return pongTime - pingTime
            }


        /**
         * The round-trip time it took to ping the remote connection
         */
        val roundtrip: Long
            get() {
                return  finishedTime - pingTime
            }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Ping

            if (packedId != other.packedId) return false
            if (pingTime != other.pingTime) return false
            if (pongTime != other.pongTime) return false
            if (finishedTime != other.finishedTime) return false

            return true
        }

        override fun hashCode(): Int {
            var result = packedId
            result = 31 * result + pingTime.hashCode()
            result = 31 * result + pongTime.hashCode()
            result = 31 * result + finishedTime.hashCode()
            return result
        }

        override fun toString(): String {
            return "PingMessage ${RmiUtils.unpackUnsignedRight(packedId)} (pingTime=$pingTime, pongTime=$pongTime, finishedTime=$finishedTime)"
        }
    }
}
