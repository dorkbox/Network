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
import dorkbox.network.Server
import dorkbox.network.ServerConfiguration
import dorkbox.network.connection.Connection
import dorkbox.storage.Storage
import dorkboxTest.network.rmi.cows.TestCow
import dorkboxTest.network.rmi.cows.TestCowImpl
import kotlinx.coroutines.*
import org.agrona.concurrent.SigInt
import org.slf4j.LoggerFactory
import sun.misc.Unsafe
import java.lang.reflect.Field
import java.util.concurrent.atomic.*

/**
 *
 */
@Suppress("UNUSED_ANONYMOUS_PARAMETER")
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
            rootLogger.level = Level.TRACE
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
        fun main(args: Array<String>) {
            val acs = AeronRmiClientServer()

            try {
                if (args.contains("client")) {
                    val configuration = ClientConfiguration()
                    configuration.settingsStore = Storage.Memory() // don't want to persist anything on disk!
                    configuration.appId = "aeron_test"
                    configuration.uniqueAeronDirectory = true

                    configuration.enableIpc = false
                    configuration.enableIPv6 = false


                    val client = acs.client(0, configuration)

                    SigInt.register {
                        client.logger.info { "Shutting down via sig-int command" }
                        runBlocking {
                            client.close(closeEverything = true, initiatedByClientClose = false, initiatedByShutdown = false)
                        }
                    }

                    client.connect("172.31.73.222", 2000) // UDP connection via loopback

                    runBlocking {
                        delay(Long.MAX_VALUE)
                    }
                    client.close()
                } else if (args.contains("server")) {
                    val server = acs.server()
                    SigInt.register {
                        server.logger.info { "Shutting down via sig-int command" }
                        runBlocking {
                            server.close(closeEverything = true, initiatedByClientClose = false, initiatedByShutdown = false)
                        }
                    }
                    runBlocking {
                        server.waitForClose()
                    }
                } else {
    //                acs.server()
    //                acs.client("localhost")

                    val clients = mutableListOf<Client<Connection>>()
                    val configuration = ClientConfiguration()
                    configuration.settingsStore = Storage.Memory() // don't want to persist anything on disk!
                    configuration.appId = "aeron_test"
                    configuration.uniqueAeronDirectory = true

                    configuration.enableIpc = false
                    configuration.enableIPv6 = false
                    configuration.port = 2001

                    repeat(10) {
                        acs.client(it, configuration.copy()).also { clients.add(it) }
                    }

                    println("Starting")

                    clients.forEachIndexed { index, client ->
                        client.connect("172.31.73.222", 2000)
                        SigInt.register {
                            client.logger.info { "Shutting down via sig-int command" }
                            runBlocking {
                                client.close(closeEverything = true, initiatedByClientClose = false, initiatedByShutdown = false)
                            }
                        }
                    }

                    System.err.println("DONE")
                    Thread.sleep(Long.MAX_VALUE)
                    clients.forEach {
                        it.close()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                println("WHOOPS")
            }
        }
    }


    @OptIn(DelicateCoroutinesApi::class)
    fun client(index: Int, configuration: ClientConfiguration): Client<Connection> {
        val client = Client<Connection>(configuration)

        client.onInit {
            logger.error("$index: initialized")
        }

        client.onConnect {
            logger.error("$index: connected")
            val remoteObject = rmi.get<TestCow>(1)
//            val remoteObjec2 = rmi.getGlobal<TestCow>(44)
            if (index == 9) {
                println("PROBLEMS!!")
            }
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

        client.onDisconnect {
            logger.error("disconnect")
        }

        client.onError { throwable ->
            logger.error("has error")
            throwable.printStackTrace()
        }

        client.onMessage<String> { message ->
            logger.error("HAS MESSAGE! $message")
        }

        return client
    }


    fun server(): Server<Connection> {
        val configuration = ServerConfiguration()
        configuration.settingsStore = Storage.Memory() // don't want to persist anything on disk!
        configuration.listenIpAddress = "*"
        configuration.maxClientCount = 50

        configuration.appId = "aeron_test"

        configuration.enableIpc = true
        configuration.enableIPv6 = false

        configuration.maxConnectionsPerIpAddress = 50

        configuration.publicationTermBufferLength = io.aeron.logbuffer.LogBufferDescriptor.TERM_MIN_LENGTH


        configuration.serialization.rmi.register(TestCow::class.java, TestCowImpl::class.java)

        val server = Server<Connection>(configuration)

//        server.rmiGlobal.save(TestCowImpl(44), 44)

        // we must always make sure that aeron is shut-down before starting again.
        runBlocking {
            if (!server.ensureStopped()) {
                throw IllegalStateException("Aeron was unable to shut down in a timely manner.")
            }
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

        server.bind(2000)
        return server
    }
}
