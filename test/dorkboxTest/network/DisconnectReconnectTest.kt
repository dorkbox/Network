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
import java.util.*

class DisconnectReconnectTest : BaseTest() {
    private val timer = Timer()
    private val reconnectCount = atomic(0)

    @Test
    fun reconnectClient() {
        run {
            val configuration = serverConfig()

            val server: Server<Connection> = Server(configuration)
            addEndPoint(server)
            runBlocking {
                server.bind(false)
            }

            server.onConnect { connection ->
                println("Disconnecting after 2 seconds.")
                delay(2000)

                println("Disconnecting....")
                connection.close()
            }
        }

        run {
            val config = clientConfig()

            val client: Client<Connection> = Client(config)
            addEndPoint(client)


            client.onDisconnect { connection ->
                println("Disconnected!")
                val count = reconnectCount.getAndIncrement()
                if (count == 3) {
                    println("Shutting down")
                    stopEndPoints()
                }
                else {
                    println("Reconnecting: $count")
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
}
