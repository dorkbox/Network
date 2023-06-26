/*
 * Copyright 2023 dorkbox, llc
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
import org.junit.Test
import java.util.concurrent.*

class RmiSpamAsyncTest : BaseTest() {
    private val RMI_ID = 12251


    @Test
    fun rmiNetworkAsync() {
        rmi()
    }

    @Test
    fun rmiIpcAsync() {
        rmi() {
            enableIpc = true
        }
    }

    /**
     * In this test the server has two objects in an object space. The client
     * uses the first remote object to get the second remote object.
     */
    private fun rmi(config: Configuration.() -> Unit = {}) {
        val server: Server<Connection>

        val mod = 100_000L
        val totalRuns = 1_000_000
        val latch = CountDownLatch(totalRuns)

        run {
            val configuration = serverConfig()
            config(configuration)

            // the logger cannot keep-up if it's on trace
            setLogLevel(Level.DEBUG)

            configuration.serialization.rmi.register(TestObject::class.java, TestObjectImpl::class.java)

            server = Server(configuration)
            addEndPoint(server)

            server.rmiGlobal.save(TestObjectImpl(latch), RMI_ID)
            server.bind(2000)
        }


        val client: Client<Connection>
        run {
            val configuration = clientConfig()
            config(configuration)

            // the logger cannot keep-up if it's on trace
            setLogLevel(Level.DEBUG)

            client = Client<Connection>(configuration)
            addEndPoint(client)

            client.onConnect {
                val remoteObject = rmi.getGlobal<TestObject>(RMI_ID)
                val obj = RemoteObject.cast(remoteObject)
                obj.async = true

                var started = false
                for (i in 0 until totalRuns) {
                    if (!started) {
                        started = true
                        logger.error("Running for $totalRuns iterations....")
                    }

                    if (i % mod == 0L) {
                        // this doesn't always output to the console. weird.
                        logger.error("$i")
                    }

                    try {
                        remoteObject.setOther(i)
                    } catch (e: Exception) {
                        logger.error("Timeout when calling RMI method")
                        e.printStackTrace()
                    }
                }
            }

            client.connect(LOCALHOST, 2000)
        }

        latch.await()
        stopEndPointsBlocking()

        waitForThreads()
    }

    private interface TestObject {
        fun setOther(value: Int): Boolean
    }

    private class TestObjectImpl(private val latch: CountDownLatch) : TestObject {
        @Override
        override fun setOther(value: Int): Boolean {
            latch.countDown()
            return true
        }
    }
}
