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
import java.io.IOException

class DisconnectReconnectTest : BaseTest() {
    private val reconnectCount = atomic(0)

    @Test
    fun reconnectClient() {
        run {
            val configuration = serverConfig()

            val server: Server<Connection> = Server(configuration)
            addEndPoint(server)
            server.bind()

            server.onConnect {
                logger.error("Disconnecting after 2 seconds.")
                delay(2000)

                logger.error("Disconnecting....")
                close()
            }
        }

        run {
            val config = clientConfig()

            val client: Client<Connection> = Client(config)
            addEndPoint(client)


            client.onDisconnect {
                logger.error("Disconnected!")

                val count = reconnectCount.getAndIncrement()
                if (count == 3) {
                    logger.error("Shutting down")
                    stopEndPoints()
                }
                else {
                    logger.error("Reconnecting: $count")
                    try {
                        client.connect(LOCALHOST)
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                }
            }

            client.connect(LOCALHOST)
        }


        waitForThreads()

        System.err.println("Connection count (after reconnecting) is: " + reconnectCount.value)
        Assert.assertEquals(4, reconnectCount.value)
    }

    @Test
    fun reconnectClientViaClientClose() {
        run {
            val configuration = serverConfig {
                uniqueAeronDirectory = true
            }

            val server: Server<Connection> = Server(configuration)
            addEndPoint(server)
            server.bind()
        }

        run {
            val config = clientConfig() {
                uniqueAeronDirectory = true
            }

            val client: Client<Connection> = Client(config)
            addEndPoint(client)

            client.onConnect {
                logger.error("Disconnecting after 2 seconds.")
                delay(2000)

                logger.error("Disconnecting....")
                client.close()
            }

            client.onDisconnect {
                logger.error("Disconnected!")

                val count = reconnectCount.getAndIncrement()
                if (count == 3) {
                    logger.error("Shutting down")
                    stopEndPoints()
                }
                else {
                    logger.error("Reconnecting: $count")
                    try {
                        client.connect(LOCALHOST)
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                }
            }

            client.connect(LOCALHOST)
        }


        waitForThreads()

        System.err.println("Connection count (after reconnecting) is: " + reconnectCount.value)
        Assert.assertEquals(4, reconnectCount.value)
    }

    interface CloseIface {
        fun close()
    }

    class CloseImpl : CloseIface {
        override fun close() {
            // the connection specific one is called instead
        }

        fun close(connection: Connection) {
            connection.logger.error { "PRE CLOSE MESSAGE!" }
            connection.close()
        }
    }


    @Test
    fun reconnectRmiClient() {
        val CLOSE_ID = 33

        run {
            val config = serverConfig()
            config.serialization.rmi.register(CloseIface::class.java)

            val server: Server<Connection> = Server(config)
            addEndPoint(server)
            server.bind()


            server.onConnect {
                logger.error("Disconnecting after 2 seconds.")
                delay(2000)

                logger.error("Disconnecting via RMI ....")

                val closerObject = rmi.get<CloseIface>(CLOSE_ID)

                // the close operation will kill the connection, preventing the response from returning.
                RemoteObject.cast(closerObject).async = true

                closerObject.close()
            }
        }

        run {
            val config = clientConfig()
            config.serialization.rmi.register(CloseIface::class.java, CloseImpl::class.java)

            val client: Client<Connection> = Client(config)
            addEndPoint(client)

            client.onInit {
                rmi.save(CloseImpl(), CLOSE_ID)
            }

            client.onDisconnect {
                logger.error("Disconnected!")

                val count = reconnectCount.getAndIncrement()
                if (count == 3) {
                    logger.error("Shutting down")
                    stopEndPoints()
                }
                else {
                    logger.error("Reconnecting: $count")
                    try {
                        client.connect(LOCALHOST)
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                }
            }

            client.connect(LOCALHOST)
        }


        waitForThreads(AUTO_FAIL_TIMEOUT*10)

        //System.err.println("Connection count (after reconnecting) is: " + reconnectCount.value)
        Assert.assertEquals(4, reconnectCount.value)
    }

    @Test
    fun manualMediaDriverAndReconnectClient() {
        val log = KotlinLogging.logger("DCUnitTest")
        // NOTE: once a config is assigned to a driver, the config cannot be changed
        val aeronDriver = AeronDriver.getDriver(serverConfig(), log)
        runBlocking {
            aeronDriver.start(log)
        }

        run {
            val serverConfiguration = serverConfig()
            val server: Server<Connection> = Server(serverConfiguration)
            addEndPoint(server)
            server.bind()

            server.onConnect {
                logger.error("Disconnecting after 2 seconds.")
                delay(2000)

                logger.error("Disconnecting....")
                close()
            }
        }

        run {
            val config = clientConfig()

            val client: Client<Connection> = Client(config)
            addEndPoint(client)


            client.onDisconnect {
                logger.error("Disconnected!")

                val count = reconnectCount.getAndIncrement()
                if (count == 3) {
                    logger.error("Shutting down")
                    stopEndPoints()
                }
                else {
                    logger.error("Reconnecting: $count")
                    try {
                        client.connect(LOCALHOST)
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                }
            }

            client.connect(LOCALHOST)
        }


        waitForThreads()
        runBlocking {
            aeronDriver.close()
        }

        //System.err.println("Connection count (after reconnecting) is: " + reconnectCount.value)
        Assert.assertEquals(4, reconnectCount.value)
    }

    @Test
    fun reconnectWithFallbackClient() {
        // this tests IPC with fallback to UDP (because the server has IPC disabled, and the client has it enabled)
        run {
            val config = serverConfig()
            config.enableIpc = false

            val server: Server<Connection> = Server(config)
            addEndPoint(server)
            server.bind()

            server.onConnect {
                logger.error("Disconnecting after 2 seconds.")
                delay(2000)

                logger.error("Disconnecting....")
                close()
            }
        }

        run {
            val config = clientConfig()
            config.enableIpc = true

            val client: Client<Connection> = Client(config)
            addEndPoint(client)


            client.onDisconnect {
                logger.error("Disconnected!")

                val count = reconnectCount.getAndIncrement()
                if (count == 3) {
                    logger.error("Shutting down")
                    stopEndPoints()
                }
                else {
                    logger.error("Reconnecting: $count")
                    try {
                        client.connect(LOCALHOST)
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                }
            }

            client.connect(LOCALHOST)
        }


        waitForThreads()

        //System.err.println("Connection count (after reconnecting) is: " + reconnectCount.value)
        Assert.assertEquals(4, reconnectCount.value)
    }

    @Test
    fun disconnectedMediaDriver() {
        val server: Server<Connection>
        run {
            val config = serverConfig()
            config.enableIpc = false
            config.uniqueAeronDirectory = true

            server = Server(config)
            addEndPoint(server)
            server.bind()

            server.onConnect {
                logger.error("Connected!")
            }
        }

        val client: Client<Connection>
        run {
            val config = clientConfig()
            config.enableIpc = false
            config.uniqueAeronDirectory = true

            client = Client(config)
            addEndPoint(client)

            client.onConnect {
                logger.error("Connected!")
            }

            client.onDisconnect {
                stopEndPoints()
            }

            client.connect(LOCALHOST)
        }

        server.close()


        waitForThreads()
    }
}
