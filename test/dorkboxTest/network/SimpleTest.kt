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

import dorkbox.network.Client
import dorkbox.network.Server
import dorkbox.network.connection.Connection
import dorkbox.util.exceptions.SecurityException
import org.junit.Assert
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.*

class SimpleTest : BaseTest() {
    var received = AtomicBoolean()
    val sent = AtomicBoolean()

    @Test
    @Throws(SecurityException::class, IOException::class)
    fun simpleIpv4() {
        received.set(false)
        sent.set(false)

        run {
            val configuration = serverConfig()
            configuration.port = 12312

            configuration.enableIPv4 = true
            configuration.enableIPv6 = false
            configuration.enableIpc = false

            val server: Server<Connection> = Server(configuration)
            addEndPoint(server)

            server.onMessage<String> { message ->
                if (message != "client") {
                    Assert.fail()
                }

                received.set(true)
                logger.error("Done, stopping endpoints")
                stopEndPoints()
            }

            server.bind()
        }

        run {
            val configuration = clientConfig()
            configuration.port = 12312
            configuration.aeronDirectory = null

            configuration.enableIPv4 = true
            configuration.enableIPv6 = false
            configuration.enableIpc = false


            val client: Client<Connection> = Client(configuration)
            addEndPoint(client)


            client.onConnect {
                sent.set(true)
                send("client")
            }

            client.connect(LOCALHOST)
        }

        waitForThreads()

        assertTrue(sent.get())
        assertTrue(received.get())
    }

    @Test
    @Throws(SecurityException::class, IOException::class)
    fun simpleIpv6() {
        received.set(false)
        sent.set(false)

        run {
            val configuration = serverConfig()
            configuration.port = 12312
            configuration.enableIPv4 = false
            configuration.enableIPv6 = true
            configuration.enableIpc = false

            val server: Server<Connection> = Server(configuration)
            addEndPoint(server)

            server.onMessage<String> { message ->
                if (message != "client") {
                    Assert.fail()
                }

                received.set(true)
                logger.error("Done, stopping endpoints")
                stopEndPoints()
            }

            server.bind()
        }

        run {
            val configuration = clientConfig()
            configuration.port = 12312
            configuration.aeronDirectory = null

            configuration.enableIPv4 = false
            configuration.enableIPv6 = true
            configuration.enableIpc = false


            val client: Client<Connection> = Client(configuration)
            addEndPoint(client)


            client.onConnect {
                sent.set(true)
                send("client")
            }

            client.connect(LOCALHOST)
        }

        waitForThreads()

        assertTrue(sent.get())
        assertTrue(received.get())
    }

    @Test
    @Throws(SecurityException::class, IOException::class)
    fun simpleIPC() {
        received.set(false)
        sent.set(false)

        run {
            val configuration = serverConfig()
            configuration.enableIpc = true

            val server: Server<Connection> = Server(configuration, "server")
            addEndPoint(server)

            server.onInit {
                logger.warn { "INIT: server" }
            }

            server.onConnect {
                logger.warn { "CONNECT: server" }
            }

            server.onMessage<String> { message ->
                if (message != "client") {
                    Assert.fail()
                }

                received.set(true)
                logger.error("Done, stopping endpoints")
                stopEndPoints()
            }

            server.bind()
        }

        run {
            val configuration = clientConfig()
            configuration.enableIpc = true

            val client: Client<Connection> = Client(configuration, "client")
            addEndPoint(client)

            client.onInit {
                logger.warn { "INIT: client" }
            }

            client.onConnect {
                logger.warn { "CONNECT: client" }
                sent.set(true)
                send("client")
            }

            client.connect(LOCALHOST)
        }

        waitForThreads()

        assertTrue(sent.get())
        assertTrue(received.get())
    }
}
