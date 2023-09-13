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
import com.esotericsoftware.kryo.KryoException
import dorkbox.hex.toHexString
import dorkbox.network.*
import dorkbox.network.aeron.AeronDriver
import dorkbox.network.connection.Connection
import dorkbox.network.connection.EndPoint
import dorkbox.os.OS
import dorkbox.storage.Storage
import dorkbox.util.entropy.Entropy
import dorkbox.util.entropy.SimpleEntropy
import dorkbox.util.exceptions.InitializationException
import kotlinx.coroutines.DelicateCoroutinesApi
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.slf4j.LoggerFactory
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.*

@OptIn(DelicateCoroutinesApi::class)
abstract class BaseTest {
    companion object {
        const val LOCALHOST = "localhost"

        const val DEBUG = true

        // wait minimum of 3 minutes before we automatically fail the unit test.
        var AUTO_FAIL_TIMEOUT: Long = if (DEBUG) 9999999999L else 180L

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

        fun pause(timeToSleep: Long) {
            Thread.sleep(timeToSleep)
        }

        fun clientConfig(block: Configuration.() -> Unit = {}): ClientConfiguration {

            val configuration = ClientConfiguration()
            configuration.appId = "network_test"
            configuration.settingsStore = Storage.Memory() // don't want to persist anything on disk!

            configuration.enableIpc = false
            configuration.enableIPv6 = false

            block(configuration)
            return configuration
        }

        fun serverConfig(block: ServerConfiguration.() -> Unit = {}): ServerConfiguration {
            val configuration = ServerConfiguration()
            configuration.appId = "network_test"
            configuration.settingsStore = Storage.Memory() // don't want to persist anything on disk!

            configuration.enableIpc = false
            configuration.enableIPv6 = false

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

    @Volatile
    private var autoFailThread: Thread? = null

    private val endPointConnections: MutableList<EndPoint<*>> = CopyOnWriteArrayList()

    @Volatile
    private var isStopping = false

    private val logger: org.slf4j.Logger = LoggerFactory.getLogger(this.javaClass.simpleName)!!

    init {
        setLogLevel(Level.TRACE)

        logger.error("---- " + this.javaClass.simpleName)

        AeronDriver.checkForMemoryLeaks()
    }


    fun addEndPoint(endPoint: EndPoint<*>, runCheck: Boolean = true) {
        if (runCheck && !endPoint.ensureStopped()) {
            throw IllegalStateException("Unable to continue, AERON was unable to stop.")
        }

        endPoint.onInit { logger.error("UNIT TEST: init $id (${uuid.toHexString()})") }
        endPoint.onConnect { logger.error("UNIT TEST: connect $id (${uuid.toHexString()})") }
        endPoint.onDisconnect { logger.error("UNIT TEST: disconnect $id (${uuid.toHexString()})") }

        endPoint.onError { logger.error("UNIT TEST: ERROR! $id (${uuid.toHexString()})", it) }

        endPoint.onError {
            if (it is KryoException) {
                logger.error("UNIT TEST: ERROR! $id (${uuid.toHexString()})", it)
                Assert.fail("KryoException caught, and it shouldn't be!")
            }
        }

        endPointConnections.add(endPoint)
    }

    /**
     * Immediately stop the endpoints. DOES NOT WAIT FOR THEM TO CLOSE!
     *
     * Can stop from inside different callbacks
     *  - message     (network event poller)
     *  - connect     (eventdispatch.connect)
     *  - disconnect  (eventdispatch.connect)
     */
    fun stopEndPoints(stopAfterMillis: Long = 0L) {
        if (isStopping) {
            return
        }

//        if (EventDispatcher.isCurrentEvent()) {
//            val mutex = Mutex(true)
//
//            // we want to redispatch, in the event we are already running inside the event dispatch
//            // this gives us the chance to properly exit/close WITHOUT blocking currentEventDispatch
//            // during the `waitForClose()` call
//            GlobalScope.launch {
//                stopEndPoints(stopAfterMillis)
//                mutex.unlock()
//            }
//
//            runBlocking {
//                mutex.withLock { }
//            }
//
//            return
//        }

        isStopping = true

        if (stopAfterMillis > 0L) {
            Thread.sleep(stopAfterMillis)
        }

        val clients = endPointConnections.filterIsInstance<Client<Connection>>()
        val servers = endPointConnections.filterIsInstance<Server<Connection>>()

        logger.error("Unit test shutting down ${clients.size} clients...")
        logger.error("Unit test shutting down ${servers.size} server...")

        // shutdown clients first
        logger.error("Closing clients...")
        clients.forEach { endPoint ->
            endPoint.close()
        }
        logger.error("NOT WAITING FOR CLIENT CLOSE.")


        // shutdown everything else (should only be servers) last
        logger.error("Closing servers...")
        servers.forEach {
            it.close()
        }
        logger.error("NOT WAITING FOR SERVER CLOSE.")

        logger.error("Closed endpoints...")
    }
    /**
     * Wait for network client/server threads to shut down for the specified time. 0 will wait forever
     *
     * it should close as close to naturally as possible, otherwise there are problems
     *
     * @param stopAfterSeconds how many seconds to wait, the default is 2 minutes.
     */
    fun waitForThreads(stopAfterSeconds: Long = AUTO_FAIL_TIMEOUT) {
        val clients = endPointConnections.filterIsInstance<Client<Connection>>()
        val servers = endPointConnections.filterIsInstance<Server<Connection>>()

        val timeoutMS = TimeUnit.SECONDS.toMillis(stopAfterSeconds)
        var successClients = true
        var successServers = true

        clients.forEach { endPoint ->
            successClients = successClients && endPoint.waitForClose(timeoutMS)
        }
        servers.forEach { endPoint ->
            successServers = successServers && endPoint.waitForClose(timeoutMS)
        }

        clients.forEach { endPoint ->
            endPoint.stopDriver()
        }
        servers.forEach { endPoint ->
            endPoint.stopDriver()
        }


        // run actions before we actually shutdown, but after we wait
        if (!successClients || !successServers) {
            Assert.fail("Shutdown latch not triggered!")
        }

        // we must always make sure that aeron is shut-down before starting again.
        clients.forEach { endPoint ->
            endPoint.ensureStopped()

            if (!Client.ensureStopped(endPoint.config.copy())) {
                throw IllegalStateException("Unable to continue, AERON client was unable to stop.")
            }
        }

        servers.forEach { endPoint ->
            endPoint.ensureStopped()

            if (!Client.ensureStopped(endPoint.config.copy())) {
                throw IllegalStateException("Unable to continue, AERON server was unable to stop.")
            }
        }

        if (!AeronDriver.areAllInstancesClosed(logger)) {
            throw RuntimeException("Unable to shutdown! There are still Aeron drivers loaded!")
        }

        logger.error("UNIT TEST, checking driver and memory leaks")

        // have to make sure that the aeron driver is CLOSED.
        Assert.assertTrue("The aeron drivers are not fully closed!", AeronDriver.areAllInstancesClosed())
        AeronDriver.checkForMemoryLeaks()

        endPointConnections.clear()

        logger.error("Finished shutting down all endpoints... ($successClients, $successServers)")
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
                stopEndPoints()
                waitForThreads()
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
