package dorkboxTest.network

import dorkbox.network.Client
import dorkbox.network.Server
import dorkbox.network.connection.Connection
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Ignore
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class MultiConnectTest : BaseTest() {
    private val reconnectCount = atomic(0)

    @Ignore
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

        runBlocking {
            launch { client1.connect(LOCALHOST) }
            launch { client2.connect(LOCALHOST) }
        }
//        GlobalScope.launch {
//            client1.connect(LOCALHOST)
//        }
//
//        GlobalScope.launch {
//            client2.connect(LOCALHOST)
//        }

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
}
