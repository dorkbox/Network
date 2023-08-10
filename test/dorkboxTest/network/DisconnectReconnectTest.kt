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
import dorkbox.network.rmi.RemoteObject
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.junit.Assert
import org.junit.Test
import java.util.concurrent.*

class DisconnectReconnectTest : BaseTest() {
    private val reconnects = 2

    @Test
    fun reconnectClient() {
        val latch = CountDownLatch(reconnects+1)
        val reconnectCount = atomic(0)

        val server = run {
            val configuration = serverConfig()

            val server: Server<Connection> = Server(configuration)
            addEndPoint(server)

            server.onConnect {
                logger.error("Disconnecting after 2 seconds.")
                delay(2000)

                logger.error("Disconnecting....")
                close()
            }

            server
        }

        val client = run {
            val config = clientConfig()

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
        stopEndPointsBlocking()
        waitForThreads()

        System.err.println("Connection count (after reconnecting) is: " + reconnectCount.value)
        Assert.assertEquals(reconnects+1, reconnectCount.value)
    }

    @Test
    fun reconnectClientViaClientClose() {
        val latch = CountDownLatch(reconnects+1)
        val reconnectCount = atomic(0)

        val server = run {
            val configuration = serverConfig {
                uniqueAeronDirectory = true
            }

            val server: Server<Connection> = Server(configuration)
            addEndPoint(server)
            server
        }

        val client = run {
            val config = clientConfig {
                uniqueAeronDirectory = true
            }

            val client: Client<Connection> = Client(config)
            addEndPoint(client)

            client.onConnect {
                logger.error("Disconnecting after 2 seconds.")
                delay(2000)

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
        stopEndPointsBlocking()
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
            runBlocking {
                connection.close()
            }
        }
    }


    @Test
    fun reconnectRmiClient() {
        val latch = CountDownLatch(reconnects+1)
        val reconnectCount = atomic(0)

        val CLOSE_ID = 33

        val server = run {
            val config = serverConfig()
            config.serialization.rmi.register(CloseIface::class.java)

            val server: Server<Connection> = Server(config)
            addEndPoint(server)

            server.onConnect {
                logger.error("Disconnecting after 2 seconds.")
                delay(2000)

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
        stopEndPointsBlocking()
        waitForThreads()

        //System.err.println("Connection count (after reconnecting) is: " + reconnectCount.value)
        Assert.assertEquals(reconnects+1, reconnectCount.value)
    }

    @Test
    fun manualMediaDriverAndReconnectClient() {
        val latch = CountDownLatch(reconnects+1)
        val reconnectCount = atomic(0)

        val log = KotlinLogging.logger("DCUnitTest")
        // NOTE: once a config is assigned to a driver, the config cannot be changed
        val aeronDriver = runBlocking {
            val driver = AeronDriver(serverConfig(), log, null)
            driver.start()
            driver
        }

        val server = run {
            val serverConfiguration = serverConfig()
            val server: Server<Connection> = Server(serverConfiguration)
            addEndPoint(server, false)

            server.onConnect {
                logger.error("Disconnecting after 2 seconds.")
                delay(2000)

                logger.error("Disconnecting....")
                close()
            }

            server
        }

        val client = run {
            val config = clientConfig()

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
        stopEndPointsBlocking()
        waitForThreads()

        runBlocking {
            aeronDriver.close()
        }

        //System.err.println("Connection count (after reconnecting) is: " + reconnectCount.value)
        Assert.assertEquals(reconnects+1, reconnectCount.value)
    }

    @Test
    fun reconnectWithFallbackClient() {
        val latch = CountDownLatch(reconnects+1)
        val reconnectCount = atomic(0)

        // this tests IPC with fallback to UDP (because the server has IPC disabled, and the client has it enabled)
        val server = run {
            val config = serverConfig()
            config.enableIpc = false

            val server: Server<Connection> = Server(config)
            addEndPoint(server)

            server.onConnect {
                logger.error("Disconnecting after 2 seconds.")
                delay(2000)

                logger.error("Disconnecting....")
                close()
            }

            server
        }

        val client = run {
            val config = clientConfig()
            config.enableIpc = true

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
        stopEndPointsBlocking()
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
        runBlocking {
            client.waitForClose()
            server.waitForClose()
        }

        waitForThreads()
    }
}
