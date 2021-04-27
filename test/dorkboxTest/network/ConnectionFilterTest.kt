package dorkboxTest.network

import dorkbox.netUtil.IPv4
import dorkbox.netUtil.IPv6
import dorkbox.network.Client
import dorkbox.network.Server
import dorkbox.network.connection.Connection
import dorkbox.network.exceptions.ClientException
import dorkbox.network.ipFilter.IpSubnetFilterRule
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.runBlocking
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

            server.onConnect { connection ->
                serverConnectSuccess.value = true
                connection.close()
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
                stopEndPoints()
            }

            runBlocking {
                try {
                    client.connect(LOOPBACK)
                } catch (e: Exception) {
                    stopEndPoints()
                    throw e
                }
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

            server.onConnect { connection ->
                serverConnectSuccess.value = true
                connection.close()
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
                stopEndPoints()
            }

            runBlocking {
                try {
                    client.connect(LOOPBACK)
                } catch (e: Exception) {
                    stopEndPoints()
                    throw e
                }
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

            server.onConnect { connection ->
                serverConnectSuccess.value = true
                connection.close()
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
                stopEndPoints()
            }

            runBlocking {
                try {
                    client.connect(LOOPBACK)
                } catch (e: Exception) {
                    stopEndPoints()
                    throw e
                }
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
            server.bind()

            server.onConnect { connection ->
                serverConnectSuccess.value = true
                connection.close()
            }
        }

        run {
            val config = clientConfig()

            val client: Client<Connection> = Client(config)
            addEndPoint(client)
            client.filter(IpSubnetFilterRule(IPv4.WILDCARD, 0))
            client.filter(IpSubnetFilterRule(IPv6.WILDCARD, 0))

            client.onConnect {
                clientConnectSuccess.value = true
            }

            client.onDisconnect {
                stopEndPoints()
            }

            runBlocking {
                try {
                    client.connect(LOOPBACK)
                } catch (e: Exception) {
                    stopEndPoints()
                    throw e
                }
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
            server.bind()
            server.filter(IpSubnetFilterRule("1.1.1.1", 32)) // this address will NEVER actually connect. we just use it for testing

            server.onConnect { connection ->
                connection.close()
            }
        }

        run {
            val config = clientConfig()

            val client: Client<Connection> = Client(config)
            addEndPoint(client)

            client.onDisconnect {
                stopEndPoints()
            }

            runBlocking {
                try {
                    client.connect(LOOPBACK)
                } catch (e: Exception) {
                    stopEndPoints()
                    throw e
                }
            }
        }

        waitForThreads()
    }

    @Test(expected = ClientException::class)
    fun rejectClient() {
        run {
            val configuration = serverConfig()

            val server: Server<Connection> = Server(configuration)
            addEndPoint(server)
            server.bind()

            server.onConnect { connection ->
                connection.close()
            }
        }

        run {
            val config = clientConfig()

            val client: Client<Connection> = Client(config)
            addEndPoint(client)
            client.filter(IpSubnetFilterRule("1.1.1.1", 32)) // this address will NEVER actually connect. we just use it for testing

            client.onDisconnect {
                stopEndPoints()
            }

            runBlocking {
                try {
                    client.connect(LOOPBACK)
                } catch (e: Exception) {
                    stopEndPoints()
                    throw e
                }
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
            server.bind()
            server.filter {
                true
            }

            server.onConnect { connection ->
                serverConnectSuccess.value = true
                connection.close()
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
                stopEndPoints()
            }

            runBlocking {
                try {
                    client.connect(LOOPBACK)
                } catch (e: Exception) {
                    stopEndPoints()
                    throw e
                }
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
            server.bind()

            server.onConnect { connection ->
                serverConnectSuccess.value = true
                connection.close()
            }
        }

        run {
            val config = clientConfig()

            val client: Client<Connection> = Client(config)
            addEndPoint(client)
            client.filter {
                true
            }

            client.onConnect {
                clientConnectSuccess.value = true
            }

            client.onDisconnect {
                stopEndPoints()
            }

            runBlocking {
                try {
                    client.connect(LOOPBACK)
                } catch (e: Exception) {
                    stopEndPoints()
                    throw e
                }
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

            server.onConnect { connection ->
                connection.close()
            }
        }

        run {
            val config = clientConfig()

            val client: Client<Connection> = Client(config)
            addEndPoint(client)

            client.onDisconnect { _ ->
                stopEndPoints()
            }

            runBlocking {
                try {
                    client.connect(LOOPBACK)
                } catch (e: Exception) {
                    stopEndPoints()
                    throw e
                }
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
            server.bind()

            server.onConnect { connection ->
                connection.close()
            }
        }

        run {
            val config = clientConfig()

            val client: Client<Connection> = Client(config)
            addEndPoint(client)
            client.filter {
                false
            }

            client.onDisconnect { _ ->
                stopEndPoints()
            }

            runBlocking {
                try {
                    client.connect(LOOPBACK)
                } catch (e: Exception) {
                    stopEndPoints()
                    throw e
                }
            }
        }

        waitForThreads()
    }
}
