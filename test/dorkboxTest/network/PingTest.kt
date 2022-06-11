package dorkboxTest.network

import ch.qos.logback.classic.Level
import dorkbox.network.Client
import dorkbox.network.Server
import dorkbox.network.connection.Connection
import dorkbox.network.ping.Ping
import dorkbox.network.rmi.RmiUtils
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

    @Test
    fun MessagePing() {
        val serverSuccess = atomic(false)
        val clientSuccess = atomic(false)

        run {
            val configuration = serverConfig()
            configuration.serialization.register(PingMessage::class.java)

            val server: Server<Connection> = Server(configuration)
            addEndPoint(server)
            server.bind()

            server.onMessage<PingMessage> { ping ->
                serverSuccess.value = true

                ping.pongTime = System.currentTimeMillis()
                send(ping)
            }
        }

        run {
            val config = clientConfig()

            val client: Client<Connection> = Client(config)
            addEndPoint(client)

            client.onMessage<PingMessage> { ping ->
                clientSuccess.value = true

                ping.finishedTime = System.currentTimeMillis()

                logger.error("out-bound: ${ping.outbound}")
                logger.error("in-bound: ${ping.inbound}")
                logger.error("round-trip: ${ping.roundtrip}")

                stopEndPoints()
            }

            client.onConnect {
                logger.error("Connecting...")

                val ping = PingMessage()
                ping.packedId = 1
                ping.pingTime = System.currentTimeMillis()

                send(ping)
            }

            client.connect(LOCALHOST)
        }

        waitForThreads()

        Assert.assertTrue(serverSuccess.value)
        Assert.assertTrue(clientSuccess.value)
    }

    class PingMessage {
        var packedId = 0
        var pingTime = 0L
        var pongTime = 0L

        @Transient
        var finishedTime = 0L

        /**
         * The time it took for the remote connection to return the ping to us. This is only accurate if the clocks are synchronized
         */
        val inbound: Long
            get() {
                return finishedTime - pongTime
            }

        /**
         * The time it took for us to ping the remote connection. This is only accurate if the clocks are synchronized.
         */
        val outbound: Long
            get() {
                return pongTime - pingTime
            }


        /**
         * The round-trip time it took to ping the remote connection
         */
        val roundtrip: Long
            get() {
                return  finishedTime - pingTime
            }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Ping

            if (packedId != other.packedId) return false
            if (pingTime != other.pingTime) return false
            if (pongTime != other.pongTime) return false
            if (finishedTime != other.finishedTime) return false

            return true
        }

        override fun hashCode(): Int {
            var result = packedId
            result = 31 * result + pingTime.hashCode()
            result = 31 * result + pongTime.hashCode()
            result = 31 * result + finishedTime.hashCode()
            return result
        }

        override fun toString(): String {
            return "PingMessage ${RmiUtils.unpackUnsignedRight(packedId)} (pingTime=$pingTime, pongTime=$pongTime, finishedTime=$finishedTime)"
        }
    }
}
