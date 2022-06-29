package dorkboxTest.network

import ch.qos.logback.classic.Level
import dorkbox.network.Client
import dorkbox.network.Server
import dorkbox.network.connection.Connection
import kotlinx.atomicfu.atomic
import org.junit.Assert
import org.junit.Test

class PingTest : BaseTest() {
    val counter = atomic(0)
    @Test
    fun RmiPing() {
        setLogLevel(Level.TRACE)

        val clientSuccess = atomic(false)

        run {
            val configuration = serverConfig()

            val server: Server<Connection> = Server(configuration)
            addEndPoint(server)
            server.bind()
        }

        run {
            val config = clientConfig()

            val client: Client<Connection> = Client(config)
            addEndPoint(client)

            client.onConnect {
                repeat(100) {
                    ping {
                        // a ping object is returned, once the round-trip is complete
                        val count = counter.getAndIncrement()
                        println(count)

                        if (count == 99) {
                            clientSuccess.value = true

                            logger.error("out-bound: $outbound")
                            logger.error("in-bound: $inbound")
                            logger.error("round-trip: $roundtrip")

                            stopEndPoints()
                        }
                    }
                }
            }

            client.connect(LOCALHOST)
        }

        waitForThreads(500)

        Assert.assertTrue(clientSuccess.value)
    }
}
