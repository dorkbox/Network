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
import dorkbox.network.Server
import dorkbox.network.ServerConfiguration
import dorkbox.network.connection.Connection
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import sun.misc.Unsafe
import java.lang.reflect.Field

/**
 *
 */
object AeronServer {
    private val LOG = LoggerFactory.getLogger(AeronServer::class.java)

    init {
        // assume SLF4J is bound to logback in the current environment
        val rootLogger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val context = rootLogger.loggerContext
        val jc = JoranConfigurator()
        jc.context = context
        context.reset() // override default configuration

//        rootLogger.setLevel(Level.OFF);

        // rootLogger.setLevel(Level.INFO);
        rootLogger.level = Level.DEBUG
        // rootLogger.setLevel(Level.TRACE);
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

    @Throws(Exception::class)
    @JvmStatic
    fun main(args: Array<String>) {
        val configuration = ServerConfiguration()
        configuration.listenIpAddress = "127.0.0.1"
        configuration.subscriptionPort = 2000
        configuration.publicationPort = 2001
        configuration.maxClientCount = 5
        configuration.maxConnectionsPerIpAddress = 5

        val server: Server<*> = Server<Connection>(configuration)

        server.filter { connection ->
            println("should this connection be allowed?")
            true
        }

        server.onConnect { connection ->
            println("connected")
        }

        server.onDisconnect { connection ->
            println("disconnect")
        }

        server.onError { throwable ->
            println("has error")
            throwable.printStackTrace()
        }

        server.onError { connection, throwable ->
            println("has error")
            throwable.printStackTrace()
        }

        server.onMessage<String> { connection, message ->
            runBlocking {
                connection.send("ECHO $message")
            }
        }

        runBlocking {
            server.bind()
        }
    }

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
    }
}
