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

import dorkbox.network.Client
import dorkbox.network.Configuration
import dorkbox.network.Server
import dorkbox.network.connection.Connection
import dorkbox.network.serialization.Serialization
import dorkboxTest.network.BaseTest
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class RmiDelayedInvocationTest : BaseTest() {
    private val iterateLock = Any()
    private val OBJ_ID = 123

    @Test
    fun rmiNetwork() {
        runBlocking {
            rmi { configuration ->
                configuration.enableIpcForLoopback = false
            }
        }
    }

    @Test
    fun rmiIpc() {
        runBlocking {
            rmi()
        }
    }

    fun register(serialization: Serialization) {
        serialization.registerRmi(TestObject::class.java, TestObjectImpl::class.java)
    }

    /**
     * In this test the server has two objects in an object space. The client
     * uses the first remote object to get the second remote object.
     */
    suspend fun rmi(config: (Configuration) -> Unit = {}) {
        run {
            val configuration = serverConfig()
            config(configuration)
            register(configuration.serialization)

            val server = Server<Connection>(configuration)
            addEndPoint(server)

            server.saveGlobalObject(TestObjectImpl(iterateLock), OBJ_ID)
            server.bind()
        }

        run {
            val configuration = clientConfig()
            config(configuration)

            val client = Client<Connection>(configuration)
            addEndPoint(client)

            client.onConnect { connection ->
                val remoteObject = connection.getGlobalObject<TestObject>(OBJ_ID)

                val totalRuns = 100
                var abort = false
                connection.logger.error("Running for $totalRuns iterations....")

                for (i in 0 until totalRuns) {
                    if (abort) {
                        break
                    }
                    if (i % 10 == 0) {
                        System.err.println(i)
                    }

                    // sometimes, this method is never called right away.
                    remoteObject.setOther(i.toFloat())
                    synchronized(iterateLock) {
                        try {
                            (iterateLock as Object).wait(1)
                        } catch (e: InterruptedException) {
                            connection.logger.error("Failed after: $i")
                            e.printStackTrace()
                            abort = true
                        }
                    }
                }
                connection.logger.error("Done with delay invocation test")

                stopEndPoints()
            }

            client.connect(LOOPBACK)
        }

        waitForThreads()
    }

    private interface TestObject {
        fun setOther(aFloat: Float)
        fun other(): Float
    }

    private class TestObjectImpl(private val iterateLock: Any) : TestObject {
        @Transient
        private val ID = idCounter.getAndIncrement()
        private var aFloat = 0f

        override fun setOther(aFloat: Float) {
            this.aFloat = aFloat

            synchronized(iterateLock) { (iterateLock as Object).notify() }
        }

        override fun other(): Float {
            return aFloat
        }

        override fun hashCode(): Int {
            return ID
        }
    }

    companion object {
        private val idCounter = AtomicInteger()
    }
}
