
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
package dorkboxTest.network.rmi.multiJVM

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.ConsoleAppender
import dorkbox.network.Client
import dorkbox.network.connection.Connection
import dorkboxTest.network.BaseTest
import dorkboxTest.network.rmi.RmiTest
import dorkboxTest.network.rmi.classes.TestCow
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

object TestClient {
    fun setup() {
        // assume SLF4J is bound to logback in the current environment
        val rootLogger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val context = rootLogger.loggerContext
        val jc = JoranConfigurator()
        jc.context = context
        context.reset() // override default configuration

//        rootLogger.setLevel(Level.OFF);

        // rootLogger.setLevel(Level.DEBUG);
        rootLogger.level = Level.TRACE
        //        rootLogger.setLevel(Level.ALL);


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

    @JvmStatic
    fun main(args: Array<String>) {
        setup()

        val configuration = BaseTest.clientConfig()
        RmiTest.register(configuration.serialization)
        configuration.serialization.register(TestCow::class.java)
        configuration.enableRemoteSignatureValidation = false

        val client = Client<Connection>(configuration)

        client.onConnect { connection ->
            System.err.println("Starting test for: Client -> Server")

            connection.createObject<TestCow>(124123) { _, remoteObject ->
                RmiTest.runTests(connection, remoteObject, 124123)
                System.err.println("DONE")

                // now send this remote object ACROSS the wire to the server (on the server, this is where the IMPLEMENTATION lives)
                connection.send(remoteObject)

                client.close()
            }
        }

        runBlocking {
            client.connect(BaseTest.LOOPBACK)
        }

        client.waitForShutdown()
    }
}
