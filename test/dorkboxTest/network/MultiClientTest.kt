package dorkboxTest.network

import ch.qos.logback.classic.Level
import dorkbox.network.Client
import dorkbox.network.Server
import dorkbox.network.connection.Connection
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Test
import java.lang.Thread.sleep

class MultiClientTest : BaseTest() {
    private val totalCount = 8 // this number is dependent on the number of CPU cores on the box!
    private val clientConnectCount = atomic(0)
    private val serverConnectCount = atomic(0)
    private val disconnectCount = atomic(0)

    @Test
    fun multiConnectClient() {
        setLogLevel(Level.TRACE)


        // clients first, so they try to connect to the server at (roughly) the same time
        val clients = mutableListOf<Client<Connection>>()
        for (i in 1..totalCount) {
            val client: Client<Connection> = Client(clientConfig(), "Client$i")
            client.onConnect {
                clientConnectCount.getAndIncrement()
                logger.error("${this.id} - Connected $i!")
            }
            client.onDisconnect {
                disconnectCount.getAndIncrement()
                logger.error("${this.id} - Disconnected $i!")
            }
            addEndPoint(client)
            clients += client
        }

        GlobalScope.launch {
            clients.forEach {
                // long connection timeout, since the more that try to connect at the same time, the longer it takes to setup aeron (since it's all shared)
                launch { it.connect(LOCALHOST, 30*totalCount) }
            }
        }

        runBlocking {
            sleep(5000L)
            println("Starting server...")
            val configuration = serverConfig()

            val server: Server<Connection> = Server(configuration)
            addEndPoint(server)
            server.onConnect {
                val count = serverConnectCount.incrementAndGet()

                logger.error("${this.id} - Connecting $count ....")
                close()

                if (count == totalCount) {
                    logger.error { "Stopping endpoints!" }
                    stopEndPoints(10000L)
                }
            }

            server.bind()
        }

        waitForThreads()

        Assert.assertEquals(totalCount, clientConnectCount.value)
        Assert.assertEquals(totalCount, serverConnectCount.value)
        Assert.assertEquals(totalCount, disconnectCount.value)
    }
}
