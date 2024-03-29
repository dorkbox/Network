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

import dorkbox.network.Client
import dorkbox.network.Configuration
import dorkbox.network.Server
import dorkbox.network.connection.Connection
import dorkbox.network.serialization.Serialization
import dorkboxTest.network.BaseTest
import org.junit.Test
import java.util.concurrent.*
import java.util.concurrent.atomic.*

class RmiDelayedInvocationTest : BaseTest() {
    private val iterateLock = Any()
    private val OBJ_ID = 123

    @Test
    fun rmiNetwork() {
        rmi()
    }

    @Test
    fun rmiIpc() {
        rmi {
            enableIpc = true
        }
    }

    fun register(serialization: Serialization<*>) {
        serialization.rmi.register(TestObject::class.java, TestObjectImpl::class.java)
    }

    /**
     * In this test the server has two objects in an object space. The client
     * uses the first remote object to get the second remote object.
     */
    fun rmi(config: Configuration.() -> Unit = {}) {
        val countDownLatch = CountDownLatch(1)
        val server = run {
            val configuration = serverConfig()
            config(configuration)
            register(configuration.serialization)

            val server = Server<Connection>(configuration)
            addEndPoint(server)

            server.rmiGlobal.save(TestObjectImpl(countDownLatch), OBJ_ID)
            server
        }

        val client = run {
            val configuration = clientConfig()
            config(configuration)

            val client = Client<Connection>(configuration)
            addEndPoint(client)

            client.onConnect {
                val remoteObject = rmi.getGlobal<TestObject>(OBJ_ID)

                val totalRuns = 100
                var abort = false
                logger.error("Running for $totalRuns iterations....")

                for (i in 0 until totalRuns) {
                    if (abort) {
                        break
                    }
                    if (i % 10 == 0) {
                        System.err.println(i)
                    }

                    // sometimes, this method is never called right away.
                    // this will also count-down the latch
                    remoteObject.setOther(i.toFloat())

                    try {
                        // it should be instant!!
                        countDownLatch.await(10, TimeUnit.SECONDS)
                    } catch (e: InterruptedException) {
                        logger.error("Failed after: $i")
                        e.printStackTrace()
                        abort = true
                    }
                }
                logger.error("Done with delay invocation test")

                stopEndPoints()
            }

            client
        }

        server.bind(2000)
        client.connect(LOCALHOST, 2000)

        waitForThreads()
    }

    private interface TestObject {
        fun setOther(aFloat: Float)
        fun other(): Float
    }

    private class TestObjectImpl(private val latch: CountDownLatch) : TestObject {
        @Transient
        private val ID = idCounter.getAndIncrement()
        private var aFloat = 0f

        override fun setOther(aFloat: Float) {
            this.aFloat = aFloat
            latch.countDown()
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
