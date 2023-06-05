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
package dorkboxTest.network

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.ConsoleAppender
import dorkbox.network.Client
import dorkbox.network.ClientConfiguration
import dorkbox.network.Configuration
import dorkbox.network.Server
import dorkbox.network.ServerConfiguration
import dorkbox.network.aeron.AeronDriver
import dorkbox.network.connection.Connection
import dorkbox.network.connection.EndPoint
import dorkbox.os.OS
import dorkbox.storage.Storage
import dorkbox.util.entropy.Entropy
import dorkbox.util.entropy.SimpleEntropy
import dorkbox.util.exceptions.InitializationException
import dorkbox.util.sync.CountingLatch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.slf4j.LoggerFactory
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.*

abstract class BaseTest {
    companion object {
        const val LOCALHOST = "localhost"

        // wait minimum of 3 minutes before we automatically fail the unit test.
        var AUTO_FAIL_TIMEOUT: Long = 180L

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

//            if (System.getProperty("logback.configurationFile") == null) {
//                val file = File("logback.xml")
//                if (file.canRead()) {
//                    System.setProperty("logback.configurationFile", file.toPath().toRealPath().toFile().toString())
//                } else {
//                    System.setProperty("logback.configurationFile", "logback.xml")
//                }
//            }

//            setLogLevel(Level.TRACE)
//            setLogLevel(Level.ERROR)
//            setLogLevel(Level.DEBUG)

            // we want our entropy generation to be simple (ie, no user interaction to generate)
            try {
                Entropy.init(SimpleEntropy::class.java)
            } catch (e: InitializationException) {
                e.printStackTrace()
            }
        }

        fun clientConfig(block: Configuration.() -> Unit = {}): ClientConfiguration {

            val configuration = ClientConfiguration()
            configuration.settingsStore = Storage.Memory() // don't want to persist anything on disk!
            configuration.port = 2000

            configuration.enableIpc = false

            block(configuration)
            return configuration
        }

        fun serverConfig(block: ServerConfiguration.() -> Unit = {}): ServerConfiguration {
            val configuration = ServerConfiguration()
            configuration.settingsStore = Storage.Memory() // don't want to persist anything on disk!
            configuration.port = 2000

            configuration.enableIpc = false

            configuration.maxClientCount = 50
            configuration.maxConnectionsPerIpAddress = 50

            block(configuration)
            return configuration
        }

        fun setLogLevel(level: Level) {
            println("Log level: $level")

            // assume SLF4J is bound to logback in the current environment
            val rootLogger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
            rootLogger.detachAndStopAllAppenders()

            val context = rootLogger.loggerContext

            val jc = JoranConfigurator()
            jc.context = context
//            jc.doConfigure(File("logback.xml").absoluteFile)
            context.reset() // override default configuration

            val encoder = PatternLayoutEncoder()
            encoder.context = context
            encoder.pattern = "%date{HH:mm:ss.SSS}  %-5level [%logger{35}] [%t] %msg%n"
            encoder.start()

            val consoleAppender = ConsoleAppender<ILoggingEvent>()
            consoleAppender.context = context
            consoleAppender.encoder = encoder
            consoleAppender.start()


            rootLogger.addAppender(consoleAppender)

            // modify the level AFTER we setup the context!

            rootLogger.level = level

            // we only want error messages
            val nettyLogger = LoggerFactory.getLogger("io.netty") as Logger
            nettyLogger.level = Level.ERROR

            // we only want error messages
            val kryoLogger = LoggerFactory.getLogger("com.esotericsoftware") as Logger
            kryoLogger.level = Level.ERROR
        }
    }

    private val latch = CountingLatch()

    @Volatile
    private var autoFailThread: Thread? = null

    private val endPointConnections: MutableList<EndPoint<*>> = CopyOnWriteArrayList()

    @Volatile
    private var isStopping = false

    private val logger: org.slf4j.Logger = LoggerFactory.getLogger(this.javaClass.simpleName)!!

    init {
        setLogLevel(Level.TRACE)

        logger.error("---- " + this.javaClass.simpleName)

        // we must always make sure that aeron is shut-down before starting again.
        if (!Server.ensureStopped(serverConfig()) || !Client.ensureStopped(clientConfig())) {
            throw IllegalStateException("Unable to continue, AERON was unable to stop.")
        }
    }


    fun addEndPoint(endPoint: EndPoint<*>) {
        endPoint.onInit { logger.error { "init" } }
        endPoint.onConnect { logger.error { "connect" } }
        endPoint.onDisconnect { logger.error { "disconnect" } }

        endPoint.onError { logger.error(it) { "ERROR!" } }

        endPointConnections.add(endPoint)
        latch.countUp()
    }

    /**
     * Immediately stop the endpoints
     */
    fun stopEndPoints(stopAfterMillis: Long = 0L) {
        runBlocking {
            stopEndPointsSuspending(stopAfterMillis)
        }
    }

    /**
     * Immediately stop the endpoints
     */
    suspend fun stopEndPointsSuspending(stopAfterMillis: Long = 0L) {
        if (isStopping) {
            return
        }
        isStopping = true

        if (stopAfterMillis > 0L) {
            delay(stopAfterMillis)
        }

        val clients = endPointConnections.filterIsInstance<Client<Connection>>()
        val servers = endPointConnections.filterIsInstance<Server<Connection>>()

        logger.error("Unit test shutting down ${clients.size} clients...")
        logger.error("Unit test shutting down ${servers.size} server...")


        // shutdown clients first
        clients.forEach { endPoint ->
            // we are ASYNC, so we must use callbacks to execute code
            endPoint.close {
                latch.countDown()
            }
        }
        clients.forEach { endPoint ->
             endPoint.waitForClose()
        }


        // shutdown everything else (should only be servers) last
        servers.forEach {
            it.close {
                latch.countDown()
            }
        }
        servers.forEach { endPoint ->
            endPoint.waitForClose()
        }


        endPointConnections.clear()

        // now we have to wait for the close events to finish running.
        val error = try {
            if (EndPoint.DEBUG_CONNECTIONS) {
                latch.await(Long.MAX_VALUE, TimeUnit.SECONDS)
            } else {
                latch.await(AUTO_FAIL_TIMEOUT, TimeUnit.SECONDS)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }

        logger.error("Shut down all endpoints... Success($error)")
    }
    /**
     * Wait for network client/server threads to shut down for the specified time. 0 will wait forever
     *
     * it should close as close to naturally as possible, otherwise there are problems
     *
     * @param stopAfterSeconds how many seconds to wait, the default is 2 minutes.
     */
    fun waitForThreads(stopAfterSeconds: Long = AUTO_FAIL_TIMEOUT, preShutdownAction: () -> Unit = {}) {
        var latchTriggered = try {
            runBlocking {
                if (stopAfterSeconds == 0L || EndPoint.DEBUG_CONNECTIONS) {
                    latch.await(Long.MAX_VALUE, TimeUnit.SECONDS)
                } else {
                    latch.await(stopAfterSeconds, TimeUnit.SECONDS)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            stopEndPoints()
            false
        }

        // run actions before we actually shutdown, but after we wait
        if (latchTriggered) {
            preShutdownAction()
        } else {
            println("LATCH NOT TRIGGERED")
            println("LATCH NOT TRIGGERED")
            println("LATCH NOT TRIGGERED")
            println("LATCH NOT TRIGGERED")
        }

        // always stop the endpoints (even if we already called this)
        try {
            stopEndPoints()
        } catch (e: Exception) {
            e.printStackTrace()
        }


        // still. we must WAIT for it to finish!
        latchTriggered = try {
            runBlocking {
                if (stopAfterSeconds == 0L || EndPoint.DEBUG_CONNECTIONS) {
                    latch.await(Long.MAX_VALUE, TimeUnit.SECONDS)
                } else {
                    latch.await(stopAfterSeconds, TimeUnit.SECONDS)
                }
            }
        } catch (e: InterruptedException) {
            e.printStackTrace()
            false
        }

        logger.error("Finished shutting ($latchTriggered) down all endpoints...")

        runBlocking {
            if (!AeronDriver.areAllInstancesClosed(logger)) {
                throw RuntimeException("Unable to shutdown! There are still Aeron drivers loaded!")
            }
        }
    }

    @Before
    fun setupFailureCheck() {
        autoFailThread = Thread({
            // not the best, but this works for our purposes. This is a TAD hacky, because we ALSO have to make sure that we
            // ARE NOT in the same thread group as netty!
            try {
                Thread.sleep(AUTO_FAIL_TIMEOUT * 1000L)

                // if the thread is interrupted, then it means we finished the test.
                LoggerFactory.getLogger(this.javaClass.simpleName).error("Test did not complete in a timely manner...")
                runBlocking {
                    stopEndPoints()
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
    }
}
