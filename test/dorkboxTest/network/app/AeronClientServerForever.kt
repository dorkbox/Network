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
import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import dorkbox.netUtil.IPv4
import dorkbox.network.Client
import dorkbox.network.ClientConfiguration
import dorkbox.network.Server
import dorkbox.network.ServerConfiguration
import dorkbox.network.connection.Connection
import dorkbox.network.ipFilter.IpSubnetFilterRule
import dorkbox.storage.Storage
import dorkbox.util.Sys
import kotlinx.atomicfu.atomic
import org.slf4j.LoggerFactory
import sun.misc.Unsafe
import java.lang.reflect.Field

/**
 * THIS WILL RUN FOREVER. It is primarily used for profiling.
 */
class AeronClientServerForever {
    companion object {
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
        rootLogger.level = Level.DEBUG
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
            encoder.pattern = "%date{HH:mm:ss.SSS}  %-5level [%logger{35}] %msg%n"
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
            val acs = AeronClientServerForever()
            val server = acs.server()
            val client = acs.client("localhost")

            server.waitForClose()
        }
    }


    fun client(remoteAddress: String) {
        val count = atomic(0L)
        val time = Stopwatch.createUnstarted()

        val configuration = ClientConfiguration()
        configuration.settingsStore = Storage.Memory() // don't want to persist anything on disk!
        configuration.appId = "aeron_test"

        configuration.enableIpc = false
//            configuration.enableIPv4 = false
        configuration.enableIPv6 = false

//            configuration.uniqueAeronDirectory = true

        val client = Client<Connection>(configuration)

        client.onInit {
            logger.error("initialized")
        }

        client.onConnect {
            logger.error("connected")
            time.start()
            send(NoGarbageObj())
        }

        client.onDisconnect {
            logger.error("disconnect")
        }

        client.onError { throwable ->
            logger.error("has error")
            throwable.printStackTrace()
        }

        client.onMessage<NoGarbageObj> { message ->
            val andIncrement = count.getAndIncrement()
            if ((andIncrement % 100000) == 0L) {
                logger.error { "Sending messages: $andIncrement" }
            }
            if (andIncrement > 0 && (andIncrement % 500000) == 0L) {
                // we are measuring roundtrip performance
                logger.error { "For 1,000,000 messages: ${Sys.getTimePrettyFull(time.elapsedNanos())}" }
                time.reset()
                time.start()
            }

            send(message)
        }

        client.connect(remoteAddress, 2000) // UDP connection via loopback


        // different ones needed
        // send - reliable
        // send - unreliable
        // send - priority (0-255 -- 255 is MAX priority) when sending, max is always sent immediately, then lower priority is sent if there is no backpressure from the MediaDriver.
        // send - IPC/local
//        runBlocking {
//            while (!client.isShutdown()) {
//                client.send("ECHO " + java.lang.Long.toUnsignedString(client.crypto.secureRandom.nextLong(), 16))
//            }
//        }


        // connection needs to know
        // is UDP or IPC
        // host address

        // RMI
        // client.get(5) -> gets from the server connection, if exists, then global.
        //                  on server, a connection local RMI object "uses" an id for global, so there will never be a conflict
        //                  using some tricks, we can make it so that it DOESN'T matter the order in which objects are created,
        //                  and can specify, if we want, the object created.
        //                 Once created though, as NEW ONE with the same ID cannot be created until the old one is removed!

//        Thread.sleep(2000L)
//        client.close()
    }


    fun server(): Server<Connection> {
        val configuration = ServerConfiguration()
        configuration.settingsStore = Storage.Memory() // don't want to persist anything on disk!
        configuration.listenIpAddress = "*"
        configuration.maxClientCount = 50
        configuration.appId = "aeron_test"

        configuration.enableIpc = false
//            configuration.enableIPv4 = false
        configuration.enableIPv6 = false

        configuration.maxConnectionsPerIpAddress = 50
        configuration.serialization.register(NoGarbageObj::class.java, NGOSerializer())

        val server = Server<Connection>(configuration)

        // we must always make sure that aeron is shut-down before starting again.
        if (!server.ensureStopped()) {
            throw IllegalStateException("Aeron was unable to shut down in a timely manner.")
        }

        server.filter(IpSubnetFilterRule(IPv4.LOCALHOST, 32))

        server.filter {
            println("should the connection $this be allowed?")
            true
        }

        server.onInit {
            logger.error("initialized")
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

        server.onMessage<NoGarbageObj> { message ->
            send(message)
        }

        server.bind(2000)

        return server
    }
}
