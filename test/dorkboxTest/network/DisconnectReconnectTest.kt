package dorkboxTest.network

import dorkbox.network.Client
import dorkbox.network.Server
import dorkbox.network.connection.Connection
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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

            server.onConnect { connection ->
                connection.logger.error("Disconnecting after 2 seconds.")
                delay(2000)

                connection.logger.error("Disconnecting....")
                connection.close()
            }
        }

        run {
            val config = clientConfig()

            val client: Client<Connection> = Client(config)
            addEndPoint(client)


            client.onDisconnect { connection ->
                connection.logger.error("Disconnected!")

                val count = reconnectCount.getAndIncrement()
                if (count == 3) {
                    connection.logger.error("Shutting down")
                    stopEndPoints()
                }
                else {
                    connection.logger.error("Reconnecting: $count")
                    try {
                        client.connect(LOOPBACK)
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                }
            }

            runBlocking {
                client.connect(LOOPBACK)
            }
        }


        waitForThreads()

        System.err.println("Connection count (after reconnecting) is: " + reconnectCount.value)
        Assert.assertEquals(4, reconnectCount.value)
    }

    @Test
    fun reconnectWithFallbackClient() {
        run {
            val config = serverConfig()
            config.enableIpc = false

            val server: Server<Connection> = Server(config)
            addEndPoint(server)
            server.bind()

            server.onConnect { connection ->
                connection.logger.error("Disconnecting after 2 seconds.")
                delay(2000)

                connection.logger.error("Disconnecting....")
                connection.close()
            }
        }

        run {
            val config = clientConfig()
            config.enableIpc = true
            config.enableIpcForLoopback = true

            val client: Client<Connection> = Client(config)
            addEndPoint(client)


            client.onDisconnect { connection ->
                connection.logger.error("Disconnected!")

                val count = reconnectCount.getAndIncrement()
                if (count == 3) {
                    connection.logger.error("Shutting down")
                    stopEndPoints()
                }
                else {
                    connection.logger.error("Reconnecting: $count")
                    try {
                        client.connect(LOOPBACK)
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                }
            }

            runBlocking {
                client.connect(LOOPBACK)
            }
        }


        waitForThreads()

        System.err.println("Connection count (after reconnecting) is: " + reconnectCount.value)
        Assert.assertEquals(4, reconnectCount.value)
    }

    @Test
    fun disconenctedMediaDriver() {
        val server: Server<Connection>
        run {
            val config = serverConfig()
            config.enableIpc = false
            config.aeronDirectoryForceUnique = true

            server = Server(config)
            addEndPoint(server)
            server.bind()

            server.onConnect { connection ->
                connection.logger.error("Connected!")
            }
        }

        val client: Client<Connection>
        run {
            val config = clientConfig()
            config.enableIpc = false
            config.aeronDirectoryForceUnique = true

            client = Client(config)
            addEndPoint(client)

            client.onConnect { connection ->
                connection.logger.error("Connected!")
            }

            client.onDisconnect {
                stopEndPoints()
            }

            runBlocking {
                client.connect(LOOPBACK)
            }
        }

        server.close()


        waitForThreads()
    }
}
