package dorkboxTest.network

import dorkbox.network.Client
import dorkbox.network.Server
import dorkbox.network.connection.Connection
import dorkbox.network.connection.ConnectionParams
import org.agrona.ExpandableDirectByteBuffer
import org.junit.Assert
import org.junit.Test
import java.security.SecureRandom

class StreamingTest : BaseTest() {

    @Test
    fun sendStreamingObject() {
        val sizeToTest = ExpandableDirectByteBuffer.MAX_BUFFER_LENGTH / 8
        val hugeData = ByteArray(sizeToTest)
        SecureRandom().nextBytes(hugeData)


        run {
            val configuration = serverConfig()

            val server: Server<Connection> = Server(configuration)
            addEndPoint(server)
            server.bind()

            server.onMessage<ByteArray> {
                println("received data, shutting down!")
                Assert.assertEquals(sizeToTest, it.size)
                Assert.assertArrayEquals(hugeData, it)
                stopEndPoints()
            }
        }

        run {
            var connectionParams: ConnectionParams<Connection>? = null
            val config = clientConfig()

            val client: Client<Connection> = Client(config) {
                connectionParams = it
                Connection(it)
            }
            addEndPoint(client)

            client.onConnect {
                val params = connectionParams ?: throw Exception("We should not have null connectionParams!")
                val publication = params.connectionInfo.publication
                logger.error { "Sending huge data: ${hugeData.size} bytes" }
                this.endPoint.send(hugeData, publication, this)
            }

            client.connect(LOCALHOST)
        }

        waitForThreads()
    }
}
