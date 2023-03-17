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
import dorkbox.netUtil.IPv4
import dorkbox.netUtil.IPv6
import dorkbox.network.Client
import dorkbox.network.Server
import dorkbox.network.connection.Connection
import dorkbox.network.exceptions.ClientException
import dorkbox.network.ipFilter.IpSubnetFilterRule
import kotlinx.atomicfu.atomic
import org.junit.Assert
import org.junit.Test

class ConnectionFilterTest : BaseTest() {
    @Test
    fun autoAcceptAll() {
        val serverConnectSuccess = atomic(false)
        val clientConnectSuccess = atomic(false)

        run {
            val configuration = serverConfig()

            val server: Server<Connection> = Server(configuration)
            addEndPoint(server)
            server.bind()

            server.onConnect {
                serverConnectSuccess.value = true
                close()
            }
        }

        run {
            val config = clientConfig()

            val client: Client<Connection> = Client(config)
            addEndPoint(client)

            client.onConnect {
                clientConnectSuccess.value = true
            }

            client.onDisconnect {
                stopEndPointsSuspending()
            }

            try {
                client.connect(LOCALHOST)
            } catch (e: Exception) {
                stopEndPoints()
                throw e
            }
        }


        waitForThreads()

        Assert.assertTrue(serverConnectSuccess.value)
        Assert.assertTrue(clientConnectSuccess.value)
    }

    @Test
    fun acceptWildcardServer() {
        val serverConnectSuccess = atomic(false)
        val clientConnectSuccess = atomic(false)

        run {
            val configuration = serverConfig()

            val server: Server<Connection> = Server(configuration)
            addEndPoint(server)
            server.bind()
            server.filter(IpSubnetFilterRule(IPv4.WILDCARD, 0))
            server.filter(IpSubnetFilterRule(IPv6.WILDCARD, 0))

            server.onConnect {
                serverConnectSuccess.value = true
                close()
            }
        }

        run {
            val config = clientConfig()

            val client: Client<Connection> = Client(config)
            addEndPoint(client)

            client.onConnect {
                clientConnectSuccess.value = true
            }

            client.onDisconnect {
                stopEndPointsSuspending()
            }

            try {
                client.connect(LOCALHOST)
            } catch (e: Exception) {
                stopEndPoints()
                throw e
            }
        }

        waitForThreads()

        Assert.assertTrue(serverConnectSuccess.value)
        Assert.assertTrue(clientConnectSuccess.value)
    }

    @Test
    fun acceptZeroCidrServer() {
        val serverConnectSuccess = atomic(false)
        val clientConnectSuccess = atomic(false)

        run {
            val configuration = serverConfig()

            val server: Server<Connection> = Server(configuration)
            addEndPoint(server)
            server.bind()
            server.filter(IpSubnetFilterRule("1.1.1.1", 0))
            server.filter(IpSubnetFilterRule("::1.1.1.1", 0)) // compressed ipv6

            server.onConnect {
                serverConnectSuccess.value = true
                close()
            }
        }

        run {
            val config = clientConfig()

            val client: Client<Connection> = Client(config)
            addEndPoint(client)

            client.onConnect {
                clientConnectSuccess.value = true
            }

            client.onDisconnect {
                stopEndPointsSuspending()
            }

            try {
                client.connect(LOCALHOST)
            } catch (e: Exception) {
                stopEndPoints()
                throw e
            }
        }

        waitForThreads()

        Assert.assertTrue(serverConnectSuccess.value)
        Assert.assertTrue(clientConnectSuccess.value)
    }

    @Test
    fun acceptWildcardClient() {
        val serverConnectSuccess = atomic(false)
        val clientConnectSuccess = atomic(false)

        run {
            val configuration = serverConfig()

            val server: Server<Connection> = Server(configuration)
            addEndPoint(server)
            server.filter(IpSubnetFilterRule(IPv4.WILDCARD, 0))
            server.filter(IpSubnetFilterRule(IPv6.WILDCARD, 0))

            server.onConnect {
                serverConnectSuccess.value = true
                close()
            }
            server.bind()
        }

        run {
            val config = clientConfig()

            val client: Client<Connection> = Client(config)
            addEndPoint(client)


            client.onConnect {
                clientConnectSuccess.value = true
            }

            client.onDisconnect {
                stopEndPointsSuspending()
            }

            try {
                client.connect(LOCALHOST)
            } catch (e: Exception) {
                stopEndPoints()
                throw e
            }
        }

        waitForThreads()

        Assert.assertTrue(serverConnectSuccess.value)
        Assert.assertTrue(clientConnectSuccess.value)
    }



    @Test(expected = ClientException::class)
    fun rejectServer() {
        run {
            val configuration = serverConfig()

            val server: Server<Connection> = Server(configuration)
            addEndPoint(server)
            server.filter(IpSubnetFilterRule("1.1.1.1", 32)) // this address will NEVER actually connect. we just use it for testing

            server.onConnect {
                close()
            }

            server.bind()
        }

        run {
            val config = clientConfig()

            val client: Client<Connection> = Client(config)
            addEndPoint(client)

            client.onDisconnect {
                stopEndPointsSuspending()
            }

            try {
                client.connect(LOCALHOST)
            } catch (e: Exception) {
                stopEndPoints()
                throw e
            }
        }

        // fail, since we should have thrown an exception
        Assert.assertFalse(true)
    }

    @Test
    fun rejectServerIpc() {
        setLogLevel(Level.TRACE)
        // we do not want to limit loopback addresses! Even with filtering, IPC is always allowed to connect

        val serverConnectSuccess = atomic(false)
        val clientConnectSuccess = atomic(false)

        run {
            val configuration = serverConfig() {
                enableIpc = true
            }

            val server: Server<Connection> = Server(configuration)
            addEndPoint(server)
            server.filter(IpSubnetFilterRule("1.1.1.1", 32)) // this address will NEVER actually connect. we just use it for testing

            server.onConnect {
                serverConnectSuccess.value = true
                close()
            }

            server.bind()
        }

        run {
            val config = clientConfig() {
                enableIpc = true
            }

            val client: Client<Connection> = Client(config)
            addEndPoint(client)

            client.onConnect {
                clientConnectSuccess.value = true
            }

            client.onDisconnect {
                stopEndPointsSuspending()
            }

            try {
                client.connect(LOCALHOST)
            } catch (e: Exception) {
                e.printStackTrace()
                stopEndPoints()
                // this is expected.
            }
        }

        waitForThreads()

        Assert.assertTrue(serverConnectSuccess.value)
        Assert.assertTrue(clientConnectSuccess.value)
    }

    @Test(expected = ClientException::class)
    fun rejectClient() {
        run {
            val configuration = serverConfig()

            val server: Server<Connection> = Server(configuration)
            addEndPoint(server)
            server.filter(IpSubnetFilterRule("1.1.1.1", 32)) // this address will NEVER actually connect. we just use it for testing
            server.onConnect {
                close()
            }

            server.bind()
        }

        run {
            val config = clientConfig()

            val client: Client<Connection> = Client(config)
            addEndPoint(client)


            client.onDisconnect {
                stopEndPointsSuspending()
            }

            try {
                client.connect(LOCALHOST)
            } catch (e: Exception) {
                stopEndPoints()
                throw e
            }
        }

        waitForThreads()
    }

    @Test
    fun acceptAllCustomServer() {
        val serverConnectSuccess = atomic(false)
        val clientConnectSuccess = atomic(false)

        run {
            val configuration = serverConfig()

            val server: Server<Connection> = Server(configuration)
            addEndPoint(server)
            server.filter {
                true
            }

            server.onConnect {
                serverConnectSuccess.value = true
                close()
            }

            server.bind()
        }

        run {
            val config = clientConfig()

            val client: Client<Connection> = Client(config)
            addEndPoint(client)

            client.onConnect {
                clientConnectSuccess.value = true
            }

            client.onDisconnect {
                stopEndPointsSuspending()
            }

            try {
                client.connect(LOCALHOST)
            } catch (e: Exception) {
                stopEndPoints()
                throw e
            }
        }


        waitForThreads()

        Assert.assertTrue(serverConnectSuccess.value)
        Assert.assertTrue(clientConnectSuccess.value)
    }

    @Test
    fun acceptAllCustomClient() {
        val serverConnectSuccess = atomic(false)
        val clientConnectSuccess = atomic(false)

        run {
            val configuration = serverConfig()

            val server: Server<Connection> = Server(configuration)
            addEndPoint(server)
            server.onConnect {
                serverConnectSuccess.value = true
                close()
            }
            server.bind()
        }

        run {
            val config = clientConfig()

            val client: Client<Connection> = Client(config)
            addEndPoint(client)


            client.onConnect {
                clientConnectSuccess.value = true
            }

            client.onDisconnect {
                stopEndPointsSuspending()
            }

            try {
                client.connect(LOCALHOST)
            } catch (e: Exception) {
                stopEndPoints()
                throw e
            }
        }


        waitForThreads()

        Assert.assertTrue(serverConnectSuccess.value)
        Assert.assertTrue(clientConnectSuccess.value)
    }


    @Test(expected = ClientException::class)
    fun rejectCustomServer() {
        run {
            val configuration = serverConfig()

            val server: Server<Connection> = Server(configuration)
            addEndPoint(server)
            server.bind()
            server.filter {
                false
            }

            server.onConnect {
                close()
            }
        }

        run {
            val config = clientConfig()

            val client: Client<Connection> = Client(config)
            addEndPoint(client)

            client.onDisconnect {
                stopEndPointsSuspending()
            }

            try {
                client.connect(LOCALHOST)
            } catch (e: Exception) {
                stopEndPoints()
                throw e
            }
        }

        waitForThreads()
    }

    @Test(expected = ClientException::class)
    fun rejectCustomClient() {
        run {
            val configuration = serverConfig()

            val server: Server<Connection> = Server(configuration)
            addEndPoint(server)
            server.filter {
                false
            }
            server.bind()

            server.onConnect {
                close()
            }
        }

        run {
            val config = clientConfig()

            val client: Client<Connection> = Client(config)
            addEndPoint(client)


            client.onDisconnect {
                stopEndPointsSuspending()
            }

            try {
                client.connect(LOCALHOST)
            } catch (e: Exception) {
                stopEndPoints()
                throw e
            }
        }

        waitForThreads()
    }
}
