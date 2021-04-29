package dorkboxTest.network

import dorkbox.network.Client
import dorkbox.network.Server
import dorkbox.network.connection.Connection
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Test

class PingTest : BaseTest() {
    @Test
    fun onServerPing() {
        val serverSuccess = atomic(false)
        val clientSuccess = atomic(false)

        run {
            val configuration = serverConfig()

            val server: Server<Connection> = Server(configuration)
            addEndPoint(server)
            server.bind()

            server.onPing { ping ->
                serverSuccess.value = true
                println("Ping info ${ping.time}")
                close()
            }
        }

        run {
            val config = clientConfig()

            val client: Client<Connection> = Client(config)
            addEndPoint(client)

            client.onConnect {
                clientSuccess.value = true

                it.ping {
                    println("received ping back! Val: $time")
                }
            }

            client.onDisconnect {
                stopEndPoints()
            }

            runBlocking {
                try {
                    client.connect(LOOPBACK)
                } catch (e: Exception) {
                    stopEndPoints()
                    throw e
                }
            }
        }


        waitForThreads()

        Assert.assertTrue(serverSuccess.value)
        Assert.assertTrue(clientSuccess.value)
    }
}
