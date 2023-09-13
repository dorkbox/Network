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
package dorkboxTest.network.app

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.ConsoleAppender
import dorkbox.network.*
import dorkbox.network.connection.Connection
import dorkbox.storage.Storage
import dorkbox.util.Sys
import dorkboxTest.network.rmi.cows.TestCow
import dorkboxTest.network.rmi.cows.TestCowImpl
import io.aeron.driver.ThreadingMode
import kotlinx.coroutines.*
import org.agrona.ExpandableDirectByteBuffer
import org.agrona.concurrent.NoOpIdleStrategy
import org.agrona.concurrent.SigInt
import org.slf4j.LoggerFactory
import sun.misc.Unsafe
import java.lang.reflect.Field
import java.security.SecureRandom
import java.util.concurrent.*
import java.util.concurrent.atomic.*

/**
 *
 */
class AeronRmiClientServer {
    companion object {
        private val counter = AtomicInteger(0)

        init {
            try {
                val theUnsafe = Unsafe::class.java.getDeclaredField("theUnsafe")
                theUnsafe.isAccessible = true
                val u = theUnsafe.get(null) as Unsafe
                val cls = Class.forName("jdk.internal.module.IllegalAccessLogger")
                val logger: Field = cls.getDeclaredField("logger")
                u.putObjectVolatile(cls, u.staticFieldOffset(logger), null)
            } catch (e: NoSuchFieldException) {
                e.printStackTrace()
            } catch (e: IllegalAccessException) {
                e.printStackTrace()
            } catch (e: ClassNotFoundException) {
                e.printStackTrace()
            }

            // assume SLF4J is bound to logback in the current environment
            val rootLogger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
            val context = rootLogger.loggerContext
            val jc = JoranConfigurator()
            jc.context = context
            context.reset() // override default configuration

//        rootLogger.setLevel(Level.OFF);

            // rootLogger.setLevel(Level.INFO);
//        rootLogger.level = Level.DEBUG
//            rootLogger.level = Level.TRACE
//        rootLogger.setLevel(Level.ALL);


            // we only want error messages
            val nettyLogger = LoggerFactory.getLogger("io.netty") as Logger
            nettyLogger.level = Level.ERROR

            // we only want error messages
            val kryoLogger = LoggerFactory.getLogger("com.esotericsoftware") as Logger
            kryoLogger.level = Level.ERROR


            val encoder = PatternLayoutEncoder()
            encoder.context = context
            encoder.pattern = "%date{HH:mm:ss.SSS} [%t] %-5level [%logger{35}] %msg%n"
            encoder.start()

            val consoleAppender = ConsoleAppender<ILoggingEvent>()
            consoleAppender.context = context
            consoleAppender.encoder = encoder
            consoleAppender.start()

            rootLogger.addAppender(consoleAppender)
        }

        /**
         * Command-line entry point.
         *
         * @param args Command-line arguments
         *
         * @throws Exception On any error
         */
        @Throws(Exception::class)
        @JvmStatic
        fun main(cliArgs: Array<String>) {

            val config = Config()

            val configProcessor = dorkbox.config.ConfigProcessor(config)
                .envPrefix("")
                .cliArguments(cliArgs)
                .process()

            fun client(acs: AeronRmiClientServer): Client<Connection> {
                val client = acs.client(0)

                client.onDisconnect {
                    logger.error("Disconnect -> Reconnect...")
                    client.reconnect()
                }

                client.onConnect {
                    logger.error("Starting test...")
                    val secureRandom = SecureRandom()
                    val sizeToTest = ExpandableDirectByteBuffer.MAX_BUFFER_LENGTH / 32

                    // don't want to time allocating the mem, just time "serializing and sending"
                    val hugeData = ByteArray(sizeToTest)
                    secureRandom.nextBytes(hugeData) // REALLY slow!!!

                    var count = 0
                    var timed = 0L

                    client.logger.error("Initializing test.")

                    // just to start it up.
                    repeat(15) {
                        if (!client.send(hugeData)) {
                            client.logger.error("Unable to send data!")
                            return@onConnect
                        }
                    }

                    client.logger.error("Starting test.")


                    val allStopwatch = Stopwatch.createStarted()
                    while (TimeUnit.NANOSECONDS.toSeconds(timed) < 5) {
//                        client.logger.error { "Starting round: $count" }

                        val roundStopwatch = Stopwatch.createStarted()
                        client.send(hugeData)
                        timed += roundStopwatch.elapsedNanos()
//                        client.logger.error { "Finished round $count in: $roundStopwatch" }
                        count++
                    }


                    val amountInMB = (count.toLong()*sizeToTest)/Sys.MEGABYTE
                    val amountInmb = (count.toLong()*sizeToTest*8)/Sys.MEGABYTE
                    val fullElapsed = allStopwatch.elapsedNanos()

                    client.logger.error("Finished $count rounds in: ${Sys.getTimePrettyFull(fullElapsed)}")
                    client.logger.error("Sending data portion took: ${Sys.getTimePrettyFull(timed)} for $amountInMB MB")

                    val timedInSeconds = TimeUnit.NANOSECONDS.toSeconds(timed)
                    client.logger.error("Rate is: ${amountInMB/timedInSeconds} MB/s")
                    client.logger.error("Rate is: ${amountInmb/timedInSeconds} mb/s")
                }

                return client
            }


            val acs = AeronRmiClientServer()
            try {
                if (config.server) {
                    val server = acs.server()

                    server.onMessage<ByteArray> {
                        logger.error("Received Byte array!")
                    }

                    server.bind(2000, 2001)
                    server.waitForClose()
                }

                else if (config.client) {
                    val client = client(acs)
                    client.connect(config.ip, 2000, 2001, 0) // UDP connection via loopback

                    client.waitForClose()
                    client.logger.error("DONE WAITING")
                } else {
                    val server = acs.server()
                    server.onMessage<ByteArray> {
                        // let the client send us data
                    }

                    val client = client(acs)

                    server.bindIpc()
                    client.connectIpc()

                    client.waitForClose()
                    client.logger.error("DONE WAITING")
                }

            } catch (e: Exception) {
                e.printStackTrace()
                println("WHOOPS")
            }
        }
    }


    fun client(index: Int): Client<Connection> {
        val configuration = ClientConfiguration()
        config(configuration)

        val client = Client<Connection>(configuration)

        client.onInit {
            logger.error("$index: initialized")
        }

        client.onConnect {
            logger.error("$index: connected")
            val remoteObject = rmi.get<TestCow>(1)
//            val remoteObjec2 = rmi.getGlobal<TestCow>(44)
//            if (index == 9) {
//                println("PROBLEMS!!")
//            }
//            logger.error("$index: starting dispatch")
//            try {
//                GlobalScope.async(Dispatchers.Default) {
//                    var startTime = System.nanoTime()
//                    logger.error("$index: started dispatch")
//
//                    var previousCount = 0
//                    while (true) {
//                        val counter = counter.getAndIncrement()
//                        try {
//    //                    ping()
//    //                    RemoteObject.cast(remoteObject).async {
//                            val value = "$index"
//                            val mooTwoValue = remoteObject.mooTwo(value)
//                            if (mooTwoValue != "moo-two: $value") {
//                                throw Exception("Value not the same!")
//                            }
//    //                    remoteObject.mooTwo("count $counter")
//    //                    }
//                        } catch (e: Exception) {
//                            e.printStackTrace()
//                            logger.error { "$index: ERROR with client " }
//                            return@async
//                        }
//
//                        val elapsedTime = ( System.nanoTime() - startTime) / 1_000_000_000.0
//                        if (index == 0 && elapsedTime > 1.0) {
//                            logger.error {
//                                val perSecond = ((counter - previousCount) / elapsedTime).toInt()
//                                "Count: $perSecond/sec" }
//                            startTime = System.nanoTime()
//                            previousCount = counter
//                        }
//                    }
//                }
//            } catch (e: Exception) {
//                e.printStackTrace()
//            }
        }

        client.onError { throwable ->
            logger.error("***has error***")
            throwable.printStackTrace()
        }

        client.onMessage<String> { message ->
            logger.error("HAS MESSAGE! $message")
        }

//        SigInt.register {
//            client.logger.info { "Shutting down via sig-int command" }
//            runBlocking {
//                client.close(closeEverything = true, initiatedByClientClose = false, initiatedByShutdown = false)
//            }
//        }


        return client
    }

    fun config(config: Configuration) {
        if (config is ServerConfiguration) {
            config.settingsStore = Storage.Property().file("config.json")
        } else {
            // don't want to persist anything on disk!
            config.settingsStore = Storage.Memory()
        }

        config.appId = "aeron_test"
        config.enableIPv6 = false

        config.enableIpc = true
//        config.enableIpc = false
//        config.uniqueAeronDirectory = true

//        config.forceAllowSharedAeronDriver = true

        // dedicate more **OOMPF** to the network
        config.threadingMode = ThreadingMode.SHARED_NETWORK
//        config.threadingMode = ThreadingMode.DEDICATED
        config.pollIdleStrategy = NoOpIdleStrategy.INSTANCE
        config.sendIdleStrategy = NoOpIdleStrategy.INSTANCE


        // only if there are enough threads on the box!
        if (Runtime.getRuntime().availableProcessors() >= 4) {
    //        config.conductorIdleStrategy = BusySpinIdleStrategy.INSTANCE
    //        config.sharedIdleStrategy = NoOpIdleStrategy.INSTANCE
            config.receiverIdleStrategy = NoOpIdleStrategy.INSTANCE
            config.senderIdleStrategy = NoOpIdleStrategy.INSTANCE
        }


        // https://blah.cloud/networks/test-jumbo-frames-working/
        // This must be a multiple of 32, and we leave some space for headers/etc
        config.networkMtuSize = 8192
        config.ipcMtuSize = io.aeron.driver.Configuration.MAX_UDP_PAYLOAD_LENGTH

        // 4 MB for receive
        config.receiveBufferSize = 4194304

        // recommended for 10gbps networks
        config.initialWindowLength = io.aeron.driver.Configuration.INITIAL_WINDOW_LENGTH_DEFAULT

        config.maxStreamSizeInMemoryMB = 128
    }


    fun server(): Server<Connection> {
        val configuration = ServerConfiguration()
        config(configuration)

        configuration.listenIpAddress = "*"
        configuration.maxClientCount = 50
        configuration.maxConnectionsPerIpAddress = 50

        configuration.serialization.rmi.register(TestCow::class.java, TestCowImpl::class.java)

        val server = Server<Connection>(configuration)

//        server.rmiGlobal.save(TestCowImpl(44), 44)

        // we must always make sure that aeron is shut-down before starting again.
        if (!server.ensureStopped()) {
            throw IllegalStateException("Aeron was unable to shut down in a timely manner.")
        }

        server.onInit {
            logger.error("initialized")
            rmi.save(TestCowImpl(1), 1)
        }

        server.onConnect {
            logger.error("connected: $this")
        }

        server.onDisconnect {
            logger.error("disconnect: $this")
        }

        server.onErrorGlobal { throwable ->
            server.logger.error("from test: has error")
            throwable.printStackTrace()
        }

        server.onError { throwable ->
            logger.error("from test: has connection error: $this")
            throwable.printStackTrace()
        }

        SigInt.register {
            server.logger.info("Shutting down via sig-int command")
            server.close()
        }


        return server
    }
}


