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
import dorkbox.network.aeron.AeronDriver
import dorkbox.network.connection.Connection
import dorkbox.network.connection.EndPoint
import dorkbox.network.rmi.RemoteObject
import kotlinx.atomicfu.atomic
import org.junit.Assert
import org.junit.Test
import org.slf4j.LoggerFactory
import java.util.concurrent.*

class DisconnectReconnectTest : BaseTest() {
    private val reconnects = 5

    @Test
    fun reconnectClient() {
        val latch = CountDownLatch(reconnects+1)
        val reconnectCount = atomic(0)

        val server = run {
            val config = serverConfig()
            config.connectionCloseTimeoutInSeconds = 0 // we want the unit test to go fast (there will be a limit with aeron linger, etc)

            val server: Server<Connection> = Server(config)
            addEndPoint(server)

            server.onConnect {
                logger.error("Disconnecting after 2 seconds.")
                pause(2000L)

                logger.error("Disconnecting....")
                close()
            }

            server
        }

        val client = run {
            val config = clientConfig()
            config.connectionCloseTimeoutInSeconds = 0 // we want the unit test to go fast (there will be a limit with aeron linger, etc)

            val client: Client<Connection> = Client(config)
            addEndPoint(client)

            client.onDisconnect {
                latch.countDown()
                logger.error("Disconnected!")

                val count = reconnectCount.getAndIncrement()
                if (count < reconnects) {
                    logger.error("Reconnecting: $count")
                    client.reconnect()
                }
            }

            client
        }

        server.bind(2000)
        client.connect(LOCALHOST, 2000)

        latch.await()
        stopEndPoints()
        waitForThreads()

        System.err.println("Connection count (after reconnecting) is: " + reconnectCount.value)
        Assert.assertEquals(reconnects+1, reconnectCount.value)
    }

    @Test
    fun reconnectClientViaClientClose() {
        val latch = CountDownLatch(reconnects+1)
        val reconnectCount = atomic(0)

        val server = run {
            val config = serverConfig {
                uniqueAeronDirectory = true
            }

            config.connectionCloseTimeoutInSeconds = 0 // we want the unit test to go fast (there will be a limit with aeron linger, etc)

            val server: Server<Connection> = Server(config)
            addEndPoint(server)
            server
        }

        val client = run {
            val config = clientConfig {
                uniqueAeronDirectory = true
            }

            config.connectionCloseTimeoutInSeconds = 0 // we want the unit test to go fast (there will be a limit with aeron linger, etc)

            val client: Client<Connection> = Client(config)
            addEndPoint(client)

            client.onConnect {
                logger.error("Disconnecting after 2 seconds.")
                pause(2000)

                logger.error("Disconnecting....")
                close()
            }

            client.onDisconnect {
                latch.countDown()
                logger.error("Disconnected!")

                val count = reconnectCount.getAndIncrement()
                if (count < reconnects) {
                    logger.error("Reconnecting: $count")
                    client.reconnect()
                }
            }

            client
        }

        server.bind(2000)
        client.connect(LOCALHOST, 2000)

        latch.await()
        stopEndPoints()
        waitForThreads()

        System.err.println("Connection count (after reconnecting) is: " + reconnectCount.value)
        Assert.assertEquals(reconnects+1, reconnectCount.value)
    }

    interface CloseIface {
        fun close()
    }

    class CloseImpl : CloseIface {
        override fun close() {
            // the connection specific one is called instead
        }

        fun close(connection: Connection) {
            connection.close()
        }
    }


    @Test
    fun reconnectRmiClient() {
        val latch = CountDownLatch(reconnects+1)
        val reconnectCount = atomic(0)

        val CLOSE_ID = 33

        val server = run {
            val config = serverConfig()
            config.connectionCloseTimeoutInSeconds = 0 // we want the unit test to go fast (there will be a limit with aeron linger, etc)
            config.serialization.rmi.register(CloseIface::class.java)

            val server: Server<Connection> = Server(config)
            addEndPoint(server)

            server.onConnect {
                logger.error("Disconnecting after 2 seconds.")
                pause(2000)

                logger.error("Disconnecting via RMI ....")

                val closerObject = rmi.get<CloseIface>(CLOSE_ID)

                // the close operation will kill the connection, preventing the response from returning.
                RemoteObject.cast(closerObject).async = true

                // this just calls connection.close() (on the client)
                closerObject.close()
            }

            server
        }

        val client = run {
            val config = clientConfig()
            config.serialization.rmi.register(CloseIface::class.java, CloseImpl::class.java)
            config.connectionCloseTimeoutInSeconds = 0 // we want the unit test to go fast (there will be a limit with aeron linger, etc)

            val client: Client<Connection> = Client(config)
            addEndPoint(client)

            client.onInit {
                rmi.save(CloseImpl(), CLOSE_ID)
            }

            client.onDisconnect {
                latch.countDown()
                logger.error("Disconnected!")

                val count = reconnectCount.getAndIncrement()
                if (count < reconnects) {
                    logger.error("Reconnecting: $count")
                    client.reconnect()
                }
            }

            client
        }

        server.bind(2000)
        client.connect(LOCALHOST, 2000)

        latch.await()
        stopEndPoints()
        waitForThreads()

        //System.err.println("Connection count (after reconnecting) is: " + reconnectCount.value)
        Assert.assertEquals(reconnects+1, reconnectCount.value)
    }

    @Test
    fun manualMediaDriverAndReconnectClient() {
        val latch = CountDownLatch(reconnects+1)
        val reconnectCount = atomic(0)

        val log = LoggerFactory.getLogger("DCUnitTest")
        // NOTE: once a config is assigned to a driver, the config cannot be changed
        val aeronDriver = AeronDriver(serverConfig(), log, null)
        aeronDriver.start()

        val server = run {
            val config = serverConfig()
            config.connectionCloseTimeoutInSeconds = 0 // we want the unit test to go fast (there will be a limit with aeron linger, etc)

            val server: Server<Connection> = Server(config)
            addEndPoint(server, false)

            server.onConnect {
                logger.error("Disconnecting after 2 seconds.")
                pause(2000)

                logger.error("Disconnecting....")
                close()
            }

            server
        }

        val client = run {
            val config = clientConfig()
            config.connectionCloseTimeoutInSeconds = 0 // we want the unit test to go fast (there will be a limit with aeron linger, etc)

            val client: Client<Connection> = Client(config)
            addEndPoint(client, false)


            client.onDisconnect {
                latch.countDown()
                logger.error("Disconnected!")

                val count = reconnectCount.getAndIncrement()
                if (count < reconnects) {
                    logger.error("Reconnecting: $count")
                    client.reconnect()
                }
            }

            client
        }

        server.bind(2000)
        client.connect(LOCALHOST, 2000)

        latch.await()
        stopEndPoints()
        waitForThreads()

        aeronDriver.close()

        //System.err.println("Connection count (after reconnecting) is: " + reconnectCount.value)
        Assert.assertEquals(reconnects+1, reconnectCount.value)
    }

    @Test
    fun reconnectWithFallbackClient() {
        if (EndPoint.DEBUG_CONNECTIONS) {
            throw RuntimeException("DEBUG_CONNECTIONS is enabled. This will cause the test to run forever!!")
        }

        val latch = CountDownLatch(reconnects+1)
        val reconnectCount = atomic(0)

        // this tests IPC with fallback to UDP (because the server has IPC disabled, and the client has it enabled)
        val server = run {
            val config = serverConfig()
            config.enableIpc = false
            config.connectionCloseTimeoutInSeconds = 0 // we want the unit test to go fast (there will be a limit with aeron linger, etc)

            val server: Server<Connection> = Server(config)
            addEndPoint(server)

            server.onConnect {
                logger.error("Disconnecting after 2 seconds.")
                pause(2000)

                logger.error("Disconnecting....")
                close()
            }

            server
        }

        val client = run {
            val config = clientConfig()
            config.enableIpc = true
            config.connectionCloseTimeoutInSeconds = 0 // we want the unit test to go fast (there will be a limit with aeron linger, etc)

            val client: Client<Connection> = Client(config)
            addEndPoint(client)


            client.onDisconnect {
                logger.error("Disconnected!")
                latch.countDown()

                val count = reconnectCount.getAndIncrement()
                if (count < reconnects) {
                    logger.error("Reconnecting: $count")
                    client.reconnect()
                }
            }

            client
        }

        server.bind(2000)
        client.connect(LOCALHOST, 2000)

        latch.await()
        stopEndPoints()
        waitForThreads()

        //System.err.println("Connection count (after reconnecting) is: " + reconnectCount.value)
        Assert.assertEquals(reconnects+1, reconnectCount.value)
    }

    @Test
    fun disconnectedMediaDriver() {
        val server = run {
            val config = serverConfig()
            config.enableIpc = false
            config.uniqueAeronDirectory = true
            config.connectionCloseTimeoutInSeconds = 0 // we want the unit test to go fast (there will be a limit with aeron linger, etc)

            val server = Server<Connection>(config)
            addEndPoint(server)

            server.onConnect {
                logger.error("Connected!")
            }
            server
        }

        val client = run {
            val config = clientConfig()
            config.enableIpc = false
            config.uniqueAeronDirectory = true
            config.connectionCloseTimeoutInSeconds = 0 // we want the unit test to go fast (there will be a limit with aeron linger, etc)

            val client = Client<Connection>(config)
            addEndPoint(client)

            client.onConnect {
                logger.error("Connected!")
            }

            client.onDisconnect {
                stopEndPoints()
            }

            client
        }

        server.bind(2000)
        client.connect(LOCALHOST, 2000)

        server.close()

        waitForThreads()
    }
}
