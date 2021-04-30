/*
 * Copyright 2020 dorkbox, llc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkboxTest.network.rmi

import ch.qos.logback.classic.Level
import dorkbox.network.Client
import dorkbox.network.Configuration
import dorkbox.network.Server
import dorkbox.network.connection.Connection
import dorkbox.network.rmi.RemoteObject
import dorkboxTest.network.BaseTest
import org.junit.Assert
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class RmiSpamSyncTest : BaseTest() {
    private val counter = AtomicLong(0)

    private val RMI_ID = 12251

    init {
        // the logger cannot keep-up if it's on trace
        setLogLevel(Level.DEBUG)
    }

    @Test
    fun rmiNetwork() {
        rmi()
    }

    @Test
    fun rmiIpc() {
        rmi() {
            enableIpc = true
        }
    }


    /**
     * In this test the server has two objects in an object space. The client
     * uses the first remote object to get the second remote object.
     */
    fun rmi(config: Configuration.() -> Unit = {}) {
        val server: Server<Connection>

        val mod = 400L
        val totalRuns = 1_000L

        run {
            val configuration = serverConfig()
            config(configuration)

            configuration.serialization.registerRmi(TestObject::class.java, TestObjectImpl::class.java)

            server = Server(configuration)
            addEndPoint(server)

            server.saveGlobalObject(TestObjectImpl(counter), RMI_ID)
            server.bind()
        }


        val client: Client<Connection>
        run {
            val configuration = clientConfig()
            config(configuration)

            client = Client(configuration)
            addEndPoint(client)

            client.onConnect { connection ->
                val remoteObject = connection.getGlobalObject<TestObject>(RMI_ID)
                val obj = remoteObject as RemoteObject
                obj.async = false

                var started = false
                for (i in 0 until totalRuns) {
                    if (!started) {
                        started = true
                        connection.logger.error("Running for $totalRuns iterations....")
                    }

                    if (i % mod == 0L) {
                        // this doesn't always output to the console. weird.
                        client.logger.error("$i")
                    }

                    try {
                        remoteObject.setOther(i)
                    } catch (e: Exception) {
                        connection.logger.error("Timeout when calling RMI method")
                        e.printStackTrace()
                    }
                }

                // have to do this first, so it will wait for the client responses!
                // if we close the client first, the connection will be closed, and the responses will never arrive to the server
                stopEndPoints()
            }

            client.connect()
        }

        waitForThreads()
        Assert.assertEquals(totalRuns, counter.get())
    }

    private interface TestObject {
        fun setOther(value: Long): Boolean
    }

    private class TestObjectImpl(private val counter: AtomicLong) : TestObject {
        @Override
        override fun setOther(value: Long): Boolean {
            counter.getAndIncrement()
            return true
        }
    }
}
