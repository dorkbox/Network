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
import dorkbox.network.Server
import dorkbox.network.ServerConfiguration
import dorkbox.network.connection.Connection
import dorkbox.storage.Storage
import org.slf4j.LoggerFactory
import sun.misc.Unsafe
import java.lang.reflect.Field

object AeronServer {
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

    @Throws(Exception::class)
    @JvmStatic
    fun main(args: Array<String>) {
        val configuration = ServerConfiguration()
        configuration.settingsStore = Storage.Memory() // don't want to persist anything on disk!
        configuration.listenIpAddress = "*"
//        configuration.listenIpAddress = "127.0.0.1"
        configuration.maxClientCount = 50

//        configuration.enableIpc = true
        configuration.enableIpc = false
        configuration.enableIPv4 = true
        configuration.enableIPv6 = true

        configuration.maxConnectionsPerIpAddress = 50

        val server: Server<*> = Server<Connection>(configuration)

        // we must always make sure that aeron is shut-down before starting again.
        if (!server.ensureStopped()) {
            throw IllegalStateException("Aeron was unable to shut down in a timely manner.")
        }

        server.filter { clientAddress, tagName ->
            println("should the connection $clientAddress be allowed?")
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

        server.onMessage<String> { message ->
            logger.error("got message! $message")
            send("ECHO $message")
        }

        server.bind(2000)

        server.waitForClose()
    }
}
