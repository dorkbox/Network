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
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.joran.JoranConfigurator
import dorkbox.network.Client
import dorkbox.network.Configuration
import dorkbox.network.Server
import dorkbox.network.connection.Connection
import dorkbox.network.rmi.RemoteObject
import dorkboxTest.network.BaseTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Test
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

class RmiDelayedInvocationSpamTest : BaseTest() {
    private val counter = AtomicLong(0)

    private val RMI_ID = 12251

    var async = true

    private fun setupLogBefore() {
        // assume SLF4J is bound to logback in the current environment
        val rootLogger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val context = rootLogger.loggerContext
        val jc = JoranConfigurator()
        jc.context = context
        context.reset() // override default configuration

        // the logger cannot keep-up if it's on trace
        rootLogger.level = Level.DEBUG
    }

    private fun setupLogAfter() {
        // assume SLF4J is bound to logback in the current environment
        val rootLogger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val context = rootLogger.loggerContext
        val jc = JoranConfigurator()
        jc.context = context
        context.reset() // override default configuration

        // the logger cannot keep-up if it's on trace
        rootLogger.level = Level.TRACE
    }

    @Test
    fun rmiNetwork() {
        runBlocking {
            async = false
            rmi { configuration ->
                configuration.enableIpcForLoopback = false
            }
        }
    }

    @Test
    fun rmiNetworkAync() {
        runBlocking {
            setupLogBefore()
            async = true
            rmi { configuration ->
                configuration.enableIpcForLoopback = false
            }
            setupLogAfter()
        }
    }

    @Test
    fun rmiIpc() {
        runBlocking {
            async = false
            rmi()
        }
    }

    @Test
    fun rmiIpcAsync() {
        runBlocking {
            setupLogBefore()
            async = true
            rmi()
            setupLogAfter()
        }
    }

    /**
     * In this test the server has two objects in an object space. The client
     * uses the first remote object to get the second remote object.
     */
    suspend fun rmi(config: (Configuration) -> Unit = {}) {
        val server: Server<Connection>

        val mod = if (async) 10_000L else 200L
        val totalRuns = if (async) 1_000_000 else 1_000

        run {
            val configuration = serverConfig()
            config(configuration)

            configuration.serialization.registerRmi(TestObject::class.java, TestObjectImpl::class.java)

            server = Server(configuration)
            addEndPoint(server)

            server.saveGlobalObject(TestObjectImpl(counter), RMI_ID)
            server.bind()
        }


        run {
            val configuration = clientConfig()
            config(configuration)

            val client = Client<Connection>(configuration)
            addEndPoint(client)

            client.onConnect { connection ->
                val remoteObject = connection.getGlobalObject<TestObject>(RMI_ID)
                val obj = remoteObject as RemoteObject
                obj.async = async

                var started = false
                for (i in 0 until totalRuns) {
                    if (!started) {
                        started = true
                        System.err.println("Running for $totalRuns iterations....")
                    }

                    if (i % mod == 0L) {
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
                stopEndPoints()
            }

            client.connect()
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
