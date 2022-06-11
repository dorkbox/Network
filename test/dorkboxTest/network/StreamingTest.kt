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

    val sizeToTest = ExpandableDirectByteBuffer.MAX_BUFFER_LENGTH / 8

    @Test
    fun sendStreamingObject() {
        run {
            val configuration = serverConfig()

            val server: Server<Connection> = Server(configuration)
            addEndPoint(server)
            server.bind()

            server.onMessage<ByteArray> {
                println("received data, shutting down!")
                Assert.assertEquals(sizeToTest, it.size)
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
                val publication = params.mediaDriverConnection.publication

                val hugeData = ByteArray(sizeToTest)
                SecureRandom().nextBytes(hugeData)

                this.endPoint.send(hugeData, publication, this)
            }

            client.connect(LOCALHOST)
        }


        waitForThreads(0)

//        System.err.println("Connection count (after reconnecting) is: " + reconnectCount.value)
//        Assert.assertEquals(4, reconnectCount.value)
    }


}
