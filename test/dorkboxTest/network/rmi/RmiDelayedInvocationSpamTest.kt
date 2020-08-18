
import dorkbox.network.Client
import dorkbox.network.Configuration
import dorkbox.network.Server
import dorkbox.network.connection.Connection
import dorkbox.network.rmi.RemoteObject
import dorkbox.network.serialization.NetworkSerializationManager
import dorkbox.util.exceptions.SecurityException
import dorkboxTest.network.BaseTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

class RmiDelayedInvocationSpamTest : BaseTest() {
    private val totalRuns = 1000000
    private val counter = AtomicLong(0)

    private val RMI_ID = 12251

    @Test
    @Throws(SecurityException::class, IOException::class)
    fun rmiNetwork() {
        rmi()
    }

    @Test
    @Throws(SecurityException::class, IOException::class)
    fun rmiLocal() {
//        rmi(object : Config() {
//            fun apply(configuration: Configuration) {
//                configuration.localChannelName = EndPoint.LOCAL_CHANNEL
//            }
//        })
    }

    fun register(serialization: NetworkSerializationManager) {
        serialization.registerRmi(TestObject::class.java, TestObjectImpl::class.java)
    }

    /**
     * In this test the server has two objects in an object space. The client
     * uses the first remote object to get the second remote object.
     */
    fun rmi(config: (Configuration) -> Unit = {}) {
        val server: Server<Connection>

        run {
            val configuration = serverConfig()
            config(configuration)
            register(configuration.serialization)

            server = Server<Connection>(configuration)
            addEndPoint(server)

            server.saveGlobalObject(TestObjectImpl(counter), RMI_ID)

            server.bind(false)
        }


        run {
            val configuration = clientConfig()
            config(configuration)
            register(configuration.serialization)

            val client = Client<Connection>(configuration)
            addEndPoint(client)

            client.onConnect { connection ->

                val remoteObject = connection.getGlobalObject<TestObject>(RMI_ID)
                val obj = remoteObject as RemoteObject
                obj.async = true

                var started = false
                for (i in 0 until totalRuns) {
                    if (!started) {
                        started = true
                        System.err.println("Running for $totalRuns iterations....")
                    }

                    if (i % 10000L == 0L) {
                        // this doesn't always output to the console. weird.
                        client.logger.error("$i")
                    }

                    try {
                        remoteObject.setOther(i.toLong())
                    } catch (e: Exception) {
                        System.err.println("Timeout when calling RMI method")
                        e.printStackTrace()
                    }
                }

                // have to do this first, so it will wait for the client responses!
                // if we close the client first, the connection will be closed, and the responses will never arrive to the server
                server.close()
                stopEndPoints()
            }

            runBlocking {
                client.connect()
            }
        }

        waitForThreads(200)
        Assert.assertEquals(totalRuns.toLong(), counter.get())
    }

    private interface TestObject {
        fun setOther(value: Long): Boolean
    }

    private class TestObjectImpl(private val counter: AtomicLong) : TestObject {
        @Override
        override fun setOther(aFloat: Long): Boolean {
            counter.getAndIncrement()
            return true
        }
    }
}
