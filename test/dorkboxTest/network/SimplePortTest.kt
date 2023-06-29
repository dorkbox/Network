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

import dorkbox.netUtil.IPv4
import dorkbox.netUtil.IPv6
import dorkbox.network.Client
import dorkbox.network.Server
import dorkbox.network.connection.Connection
import dorkbox.util.exceptions.SecurityException
import org.junit.Assert
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.*

class SimplePortTest : BaseTest() {
    private var received = AtomicBoolean()
    private val sent = AtomicBoolean()

    enum class ConnectType(val ip4: Boolean, val ip6: Boolean, val ipc: Boolean) {
        IPC(false, false, true),
        IPC4(true, false, true),
        IPC6(false, true, true),
        IPC46(true, true, true),
        IPC64(true, true, true),
        IP4(true, false, false),
        IP6(false, true, false),
        IP46(true, true, false),
        IP64(true, true, false)
    }


    @Test
    @Throws(SecurityException::class, IOException::class)
    fun simpleIp4Server() {
        simpleServerShutdown(ConnectType.IP4)
    }

    @Test
    @Throws(SecurityException::class, IOException::class)
    fun simpleIp6Server() {
        simpleServerShutdown(ConnectType.IP6)
    }

    @Test
    @Throws(SecurityException::class, IOException::class)
    fun simpleIp46Server() {
        simpleServerShutdown(ConnectType.IP46)
    }

    @Test
    @Throws(SecurityException::class, IOException::class)
    fun simpleIp64Server() {
        simpleServerShutdown(ConnectType.IP64)
    }

    @Test
    @Throws(SecurityException::class, IOException::class)
    fun simpleIpcServer() {
        simpleServerShutdown(ConnectType.IPC)
    }

    @Test
    @Throws(SecurityException::class, IOException::class)
    fun simpleIpc4FallbackServer() {
        simpleServerShutdown(ConnectType.IPC4, ConnectType.IPC)
    }

    @Test
    @Throws(SecurityException::class, IOException::class)
    fun simpleIpc6FallbackServer() {
        simpleServerShutdown(ConnectType.IPC6, ConnectType.IPC)
    }

    @Test
    @Throws(SecurityException::class, IOException::class)
    fun simpleIpc46FallbackServer() {
        simpleServerShutdown(ConnectType.IPC46, ConnectType.IPC)
    }

    @Test
    @Throws(SecurityException::class, IOException::class)
    fun simpleIpc64FallbackServer() {
        simpleServerShutdown(ConnectType.IPC64, ConnectType.IPC)
    }


    ////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////

    @Test
    @Throws(SecurityException::class, IOException::class)
    fun simpleIp4Client() {
        simpleClientShutdown(ConnectType.IP4)
    }

    @Test
    @Throws(SecurityException::class, IOException::class)
    fun simpleIp6Client() {
        simpleClientShutdown(ConnectType.IP6)
    }

    @Test
    @Throws(SecurityException::class, IOException::class)
    fun simpleIp46Client() {
        simpleClientShutdown(ConnectType.IP46)
    }

    @Test
    @Throws(SecurityException::class, IOException::class)
    fun simpleIp64Client() {
        simpleClientShutdown(ConnectType.IP64)
    }

    @Test
    @Throws(SecurityException::class, IOException::class)
    fun simpleIpcClient() {
        simpleClientShutdown(ConnectType.IPC)
    }

    @Test
    @Throws(SecurityException::class, IOException::class)
    fun simpleIpc4FallbackClient() {
        simpleClientShutdown(ConnectType.IPC4, ConnectType.IPC)
    }

    @Test
    @Throws(SecurityException::class, IOException::class)
    fun simpleIpc6FallbackClient() {
        simpleClientShutdown(ConnectType.IPC6, ConnectType.IPC)
    }

    @Test
    @Throws(SecurityException::class, IOException::class)
    fun simpleIpc46FallbackClient() {
        simpleClientShutdown(ConnectType.IPC46, ConnectType.IPC)
    }

    @Test
    @Throws(SecurityException::class, IOException::class)
    fun simpleIpc64FallbackClient() {
        simpleClientShutdown(ConnectType.IPC64, ConnectType.IPC)
    }

    // shutdown from the server
    private fun simpleServerShutdown(clientType: ConnectType, serverType: ConnectType = clientType) {
        received.set(false)
        sent.set(false)

        val server = run {
            val configuration = serverConfig()

            configuration.enableIPv4 = serverType.ip4
            configuration.enableIPv6 = serverType.ip6
            configuration.enableIpc = serverType.ipc

            val server: Server<Connection> = Server(configuration)
            addEndPoint(server)

            server.onMessage<String> { message ->
                if (message != "client") {
                    stopEndPoints()
                    Assert.fail("Wrong message!")
                }

                if (this.isNetwork && this.remotePort != 2400) {
                    stopEndPoints()
                    Assert.fail("Wrong port!")
                }

                received.set(true)
                logger.error("Done, stopping endpoints")

                // this must NOT be on the disconnect thread, because we cancel it!
                stopEndPoints()
            }

            server
        }

        val client = run {
            val configuration = clientConfig()

            configuration.port = 2400 // this is the port the client will receive return traffic on
            configuration.enableIPv4 = clientType.ip4
            configuration.enableIPv6 = clientType.ip6
            configuration.enableIpc = clientType.ipc


            val client: Client<Connection> = Client(configuration)
            addEndPoint(client)

            client.onConnect {
                sent.set(true)
                send("client")
            }

            client
        }

        server.bind(12312)
        when (clientType) {
            ConnectType.IPC -> { client.connectIpc() }
            ConnectType.IPC4 -> { client.connect(IPv4.LOCALHOST, 12312) }
            ConnectType.IPC6 -> { client.connect(IPv6.LOCALHOST, 12312) }
            ConnectType.IPC46 -> { client.connect(IPv4.LOCALHOST, 12312) }
            ConnectType.IPC64 -> { client.connect(IPv6.LOCALHOST, 12312) }
            ConnectType.IP4 -> { client.connect(IPv4.LOCALHOST, 12312) }
            ConnectType.IP6 -> { client.connect(IPv6.LOCALHOST, 12312) }
            ConnectType.IP46 -> { client.connect(IPv4.LOCALHOST, 12312) }
            ConnectType.IP64 -> { client.connect(IPv6.LOCALHOST, 12312) }
        }


        waitForThreads()

        assertTrue(sent.get())
        assertTrue(received.get())
    }

    // shutdown from the client
    private fun simpleClientShutdown(clientType: ConnectType, serverType: ConnectType = clientType) {
        received.set(false)
        sent.set(false)

        val server = run {
            val configuration = serverConfig()

            configuration.enableIPv4 = serverType.ip4
            configuration.enableIPv6 = serverType.ip6
            configuration.enableIpc = serverType.ipc

            val server: Server<Connection> = Server(configuration)
            addEndPoint(server)

            server.onMessage<String> { message ->
                if (message != "client") {
                    stopEndPoints()
                    Assert.fail("Wrong message!")
                }

                if (this.isNetwork && this.remotePort != 2400) {
                    stopEndPoints()
                    Assert.fail("Wrong port!")
                }

                received.set(true)
                logger.error("Done, stopping endpoints")
                close()
            }

            server
        }

        val client = run {
            val configuration = clientConfig()

            configuration.port = 2400 // this is the port the client will receive return traffic on
            configuration.enableIPv4 = clientType.ip4
            configuration.enableIPv6 = clientType.ip6
            configuration.enableIpc = clientType.ipc


            val client: Client<Connection> = Client(configuration)
            addEndPoint(client)

            client.onConnect {
                sent.set(true)
                send("client")
            }

            client.onDisconnect {
                stopEndPoints()
            }

            client
        }

        server.bind(12312)
        when (clientType) {
            ConnectType.IPC -> { client.connectIpc() }
            ConnectType.IPC4 -> { client.connect(IPv4.LOCALHOST, 12312) }
            ConnectType.IPC6 -> { client.connect(IPv6.LOCALHOST, 12312) }
            ConnectType.IPC46 -> { client.connect(IPv4.LOCALHOST, 12312) }
            ConnectType.IPC64 -> { client.connect(IPv6.LOCALHOST, 12312) }
            ConnectType.IP4 -> { client.connect(IPv4.LOCALHOST, 12312) }
            ConnectType.IP6 -> { client.connect(IPv6.LOCALHOST, 12312) }
            ConnectType.IP46 -> { client.connect(IPv4.LOCALHOST, 12312) }
            ConnectType.IP64 -> { client.connect(IPv6.LOCALHOST, 12312) }
        }

        waitForThreads()

        assertTrue(sent.get())
        assertTrue(received.get())
    }
}
