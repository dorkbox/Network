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
package dorkboxTest.network

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.ConsoleAppender
import dorkbox.netUtil.IPv4
import dorkbox.network.Client
import dorkbox.network.ClientConfiguration
import dorkbox.network.connection.Connection
import dorkbox.network.ipFilter.IpSubnetFilterRule
import dorkbox.storage.Storage
import org.slf4j.LoggerFactory
import sun.misc.Unsafe
import java.lang.reflect.Field
import java.text.SimpleDateFormat
import java.util.*

/**
 *
 */
@Suppress("UNUSED_ANONYMOUS_PARAMETER")
object AeronClient {

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
        encoder.pattern = "%date{HH:mm:ss.SSS}  %-5level [%logger{35}] %msg%n"
        encoder.start()
        val consoleAppender = ConsoleAppender<ILoggingEvent>()
        consoleAppender.context = context
        consoleAppender.encoder = encoder
        consoleAppender.start()
        rootLogger.addAppender(consoleAppender)
    }

    fun convertLongToTime(time: Long): String {
        val date = Date(time)
        val format = SimpleDateFormat("yyyy.MM.dd HH:mm:ss")
        return format.format(date)
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
        val configuration = ClientConfiguration()
        configuration.settingsStore = Storage.Memory() // don't want to persist anything on disk!
        configuration.port = 2000

        configuration.enableIpc = true
//        configuration.enableIpc = false
//        configuration.enableIPv4 = false
//        configuration.enableIPv6 = true
//        configuration.uniqueAeronDirectory = true

        val client = Client<Connection>(configuration)

        client.filter(IpSubnetFilterRule(IPv4.LOCALHOST, 32))

        client.filter {
            println("should this connection be allowed?")
            true
        }

        client.onConnect {
            println("connected")
        }

        client.onDisconnect {
            println("disconnect")
        }

        client.onError { throwable ->
            println("has error")
            throwable.printStackTrace()
        }

        client.onMessage<String> { message ->
            println("HAS MESSAGE!")
            println(message)
        }

        client.connect("127.0.0.1") // UDP connection via loopback


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
        //                  Once created though, as NEW ONE with the same ID cannot be created until the old one is removed!
        client.close()
    }
}
