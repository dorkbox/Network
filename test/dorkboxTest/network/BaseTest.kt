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
 *
 * Copyright (c) 2008, Nathan Sweet
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following
 * conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided with the distribution.
 * - Neither the name of Esoteric Software nor the names of its contributors may be used to endorse or promote products derived
 * from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING,
 * BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT
 * SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package dorkboxTest.network

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.ConsoleAppender
import dorkbox.network.Client
import dorkbox.network.Configuration
import dorkbox.network.Server
import dorkbox.network.ServerConfiguration
import dorkbox.network.connection.EndPoint
import dorkbox.network.storage.types.MemoryStore
import dorkbox.os.OS
import dorkbox.util.entropy.Entropy
import dorkbox.util.entropy.SimpleEntropy
import dorkbox.util.exceptions.InitializationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.slf4j.LoggerFactory
import java.lang.Thread.sleep
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

abstract class BaseTest {
    private val lock = Any()
    private var latch = CountDownLatch(1)

    @Volatile
    private var autoFailThread: Thread? = null

    companion object {
        const val LOOPBACK = "loopback"
        fun clientConfig(block: Configuration.() -> Unit = {}): Configuration {
            val configuration = Configuration()
            configuration.settingsStore = MemoryStore.type() // don't want to persist anything on disk!
            configuration.subscriptionPort = 2000
            configuration.publicationPort = 2001

            block(configuration)
            return configuration
        }

        fun serverConfig(block: ServerConfiguration.() -> Unit = {}): ServerConfiguration {
            val configuration = ServerConfiguration()
            configuration.settingsStore = MemoryStore.type() // don't want to persist anything on disk!

            configuration.subscriptionPort = 2000
            configuration.publicationPort = 2001

            configuration.maxClientCount = 5
            configuration.maxConnectionsPerIpAddress = 5

            block(configuration)

            return configuration
        }

        // wait minimum of 2 minutes before we automatically fail the unit test.
        const val AUTO_FAIL_TIMEOUT: Long = 120

        init {
            if (OS.javaVersion >= 9) {
                // disableAccessWarnings
                try {
                    val unsafeClass = Class.forName("sun.misc.Unsafe")
                    val field: Field = unsafeClass.getDeclaredField("theUnsafe")
                    field.isAccessible = true
                    val unsafe: Any = field.get(null)
                    val putObjectVolatile: Method = unsafeClass.getDeclaredMethod("putObjectVolatile", Any::class.java, Long::class.javaPrimitiveType, Any::class.java)
                    val staticFieldOffset: Method = unsafeClass.getDeclaredMethod("staticFieldOffset", Field::class.java)
                    val loggerClass = Class.forName("jdk.internal.module.IllegalAccessLogger")
                    val loggerField: Field = loggerClass.getDeclaredField("logger")
                    val offset = staticFieldOffset.invoke(unsafe, loggerField) as Long
                    putObjectVolatile.invoke(unsafe, loggerClass, offset, null)
                } catch (ignored: Exception) {
                }
            }

            // we want our entropy generation to be simple (ie, no user interaction to generate)
            try {
                Entropy.init(SimpleEntropy::class.java)
            } catch (e: InitializationException) {
                e.printStackTrace()
            }
        }
    }

    private val endPointConnections: MutableList<EndPoint<*>> = CopyOnWriteArrayList()

    @Volatile
    private var isStopping = false

    init {
        println("---- " + this.javaClass.simpleName)

//        setLogLevel(Level.INFO)
        setLogLevel(Level.TRACE)
//        setLogLevel(Level.DEBUG)
    }

    fun setLogLevel(level: Level) {
        // assume SLF4J is bound to logback in the current environment
        val rootLogger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val context = rootLogger.loggerContext
        val jc = JoranConfigurator()
        jc.context = context
        context.reset() // override default configuration

        rootLogger.level = level

        // we only want error messages
        val kryoLogger = LoggerFactory.getLogger("com.esotericsoftware") as Logger
        kryoLogger.level = Level.ERROR


        val encoder = PatternLayoutEncoder()
        encoder.context = context
        encoder.pattern = "%date{HH:mm:ss.SSS}  %-5level [%logger{35}] %msg%n"
        encoder.start()
        val consoleAppender = ConsoleAppender<ILoggingEvent>()
        consoleAppender.context = context
        consoleAppender.encoder = encoder
        consoleAppender.start()
        rootLogger.addAppender(consoleAppender)
    }


    fun addEndPoint(endPointConnection: EndPoint<*>) {
        endPointConnections.add(endPointConnection)
        synchronized(lock) { latch = CountDownLatch(endPointConnections.size + 1) }
    }

    /**
     * Immediately stop the endpoints
     */
    fun stopEndPoints(stopAfterMillis: Long = 0) {
        if (isStopping) {
            return
        }
        isStopping = true

        // not the best, but this works for our purposes. This is a TAD hacky, because we ALSO have to make sure that we
        // ARE NOT in the same thread group as netty!
        sleep(stopAfterMillis)

        synchronized(lock) {}

        // shutdown clients first
        for (endPoint in endPointConnections) {
            if (endPoint is Client) {
                endPoint.close()
                latch.countDown()
            }
        }
        // shutdown servers last
        for (endPoint in endPointConnections) {
            if (endPoint is Server) {
                endPoint.close()
                latch.countDown()
            }
        }

        // we start with "1", so make sure to end it
        latch.countDown()
        endPointConnections.clear()
    }
    /**
     * Wait for network client/server threads to shutdown for the specified time.
     *
     * it should close as close to naturally as possible, otherwise there are problems
     *
     * @param stopAfterSeconds how many seconds to wait, the default is 2 minutes.
     */
    fun waitForThreads(stopAfterSeconds: Long = AUTO_FAIL_TIMEOUT) {
        synchronized(lock) {}
        try {
            if (stopAfterSeconds == 0L) {
                latch.await(Long.MAX_VALUE, TimeUnit.SECONDS)
            } else {
                latch.await(stopAfterSeconds, TimeUnit.SECONDS)
            }

        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    @Before
    fun setupFailureCheck() {
        autoFailThread = Thread(Runnable {
            // not the best, but this works for our purposes. This is a TAD hacky, because we ALSO have to make sure that we
            // ARE NOT in the same thread group as netty!
            try {
                Thread.sleep(AUTO_FAIL_TIMEOUT * 1000L)

                // if the thread is interrupted, then it means we finished the test.
                System.err.println("Test did not complete in a timely manner...")
                runBlocking {
                    stopEndPoints(0L)
                }
                Assert.fail("Test did not complete in a timely manner.")
            } catch (ignored: InterruptedException) {
            }
        }, "UnitTest timeout fail condition")
        autoFailThread!!.isDaemon = true
        // autoFailThread.start();
    }

    @After
    fun cancelFailureCheck() {
        if (autoFailThread != null) {
            autoFailThread!!.interrupt()
            autoFailThread = null
        }

        // Give sockets a chance to close before starting the next test.
        try {
            Thread.sleep(1000)
        } catch (ignored: InterruptedException) {
        }
    }
}
