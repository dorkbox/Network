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

class SimpleTest : BaseTest() {
    var received = AtomicBoolean()
    val sent = AtomicBoolean()

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
    fun simpleIp4() {
        simple(ConnectType.IP4)
    }

    @Test
    @Throws(SecurityException::class, IOException::class)
    fun simpleIp6() {
        simple(ConnectType.IP6)
    }

    @Test
    @Throws(SecurityException::class, IOException::class)
    fun simpleIp46() {
        simple(ConnectType.IP46)
    }

    @Test
    @Throws(SecurityException::class, IOException::class)
    fun simpleIp64() {
        simple(ConnectType.IP64)
    }

    @Test
    @Throws(SecurityException::class, IOException::class)
    fun simpleIpc() {
        simple(ConnectType.IPC)
    }

    @Test
    @Throws(SecurityException::class, IOException::class)
    fun simpleIpc4Fallback() {
        simple(ConnectType.IPC4, ConnectType.IPC)
    }

    @Test
    @Throws(SecurityException::class, IOException::class)
    fun simpleIpc6Fallback() {
        simple(ConnectType.IPC6 , ConnectType.IPC)
    }

    @Test
    @Throws(SecurityException::class, IOException::class)
    fun simpleIpc46Fallback() {
        simple(ConnectType.IPC46 , ConnectType.IPC)
    }

    @Test
    @Throws(SecurityException::class, IOException::class)
    fun simpleIpc64Fallback() {
        simple(ConnectType.IPC64 , ConnectType.IPC)
    }

    private fun simple(clientType: ConnectType, serverType: ConnectType = clientType) {
        received.set(false)
        sent.set(false)

        run {
            val configuration = serverConfig()
            configuration.port = 12312

            configuration.enableIPv4 = serverType.ip4
            configuration.enableIPv6 = serverType.ip6
            configuration.enableIpc = serverType.ipc

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

            configuration.enableIPv4 = clientType.ip4
            configuration.enableIPv6 = clientType.ip6
            configuration.enableIpc = clientType.ipc


            val client: Client<Connection> = Client(configuration)
            addEndPoint(client)

            client.onConnect {
                sent.set(true)
                send("client")
            }

            when (clientType) {
                ConnectType.IPC -> client.connect()
                ConnectType.IPC4 -> client.connect(IPv4.LOCALHOST)
                ConnectType.IPC6 -> client.connect(IPv6.LOCALHOST)
                ConnectType.IPC46 -> client.connect(IPv4.LOCALHOST)
                ConnectType.IPC64 -> client.connect(IPv6.LOCALHOST)
                ConnectType.IP4 -> client.connect(IPv4.LOCALHOST)
                ConnectType.IP6 -> client.connect(IPv6.LOCALHOST)
                ConnectType.IP46 -> client.connect(IPv4.LOCALHOST)
                ConnectType.IP64 -> client.connect(IPv6.LOCALHOST)
            }
        }

        waitForThreads()

        assertTrue(sent.get())
        assertTrue(received.get())
    }
}
