package dorkboxTest.network

import dorkbox.network.Client
import dorkbox.network.Server
import dorkbox.network.aeron.AeronDriver
import dorkbox.network.connection.Connection
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

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


        waitForThreads(0)

        System.err.println("Connection count (after reconnecting) is: " + reconnectCount.value)
        Assert.assertEquals(4, reconnectCount.value)
    }

    @Test
    fun reconnectClientViaClientClose() {
        run {
            val configuration = serverConfig() {
                aeronDirectoryForceUnique = true
            }

            val server: Server<Connection> = Server(configuration)
            addEndPoint(server)
            server.bind()
        }

        run {
            val config = clientConfig() {
                aeronDirectoryForceUnique = true
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


        waitForThreads(0)

        System.err.println("Connection count (after reconnecting) is: " + reconnectCount.value)
        Assert.assertEquals(4, reconnectCount.value)
    }

    @Test
    fun multiConnectClient() {
        // clients first, so they try to connect to the server at (roughly) the same time
        val config = clientConfig()

        val client1: Client<Connection> = Client(config)
        val client2: Client<Connection> = Client(config)

        addEndPoint(client1)
        addEndPoint(client2)
        client1.onDisconnect {
            logger.error("Disconnected 1!")
        }
        client2.onDisconnect {
            logger.error("Disconnected 2!")
        }

        GlobalScope.launch {
            client1.connect(LOCALHOST)
        }

        GlobalScope.launch {
            client2.connect(LOCALHOST)
        }

        println("Starting server...")

        run {
            val configuration = serverConfig()

            val server: Server<Connection> = Server(configuration)
            addEndPoint(server)
            server.bind()

            server.onConnect {
                logger.error("Disconnecting after 10 seconds.")
                delay(10.seconds)

                logger.error("Disconnecting....")
                close()
            }
        }

        waitForThreads()

        System.err.println("Connection count (after reconnecting) is: " + reconnectCount.value)
        Assert.assertEquals(4, reconnectCount.value)
    }

    interface CloseIface {
        suspend fun close()
    }

    class CloseImpl : CloseIface {
        override suspend fun close() {
            // the connection specific one is called instead
        }

        suspend fun close(connection: Connection) {
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
                closerObject.close()
            }
        }

        run {
            val config = clientConfig()
            config.serialization.rmi.register(CloseIface::class.java, CloseImpl::class.java)

            val client: Client<Connection> = Client(config)
            addEndPoint(client)

            client.onConnect {
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


        waitForThreads(0)

        System.err.println("Connection count (after reconnecting) is: " + reconnectCount.value)
        Assert.assertEquals(4, reconnectCount.value)
    }

    @Test
    fun manualMediaDriverAndReconnectClient() {
        // NOTE: once a config is assigned to a driver, the config cannot be changed
        val aeronDriver = AeronDriver(serverConfig())
        runBlocking {
            aeronDriver.start()
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
        aeronDriver.close()

        System.err.println("Connection count (after reconnecting) is: " + reconnectCount.value)
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

        System.err.println("Connection count (after reconnecting) is: " + reconnectCount.value)
        Assert.assertEquals(4, reconnectCount.value)
    }

    @Test
    fun disconnectedMediaDriver() {
        val server: Server<Connection>
        run {
            val config = serverConfig()
            config.enableIpc = false
            config.aeronDirectoryForceUnique = true

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
            config.aeronDirectoryForceUnique = true

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
