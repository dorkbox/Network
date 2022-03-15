package dorkboxTest.network

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
                stopEndPoints()
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
                stopEndPoints()
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
                stopEndPoints()
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
            client.filter(IpSubnetFilterRule(IPv4.WILDCARD, 0))
            client.filter(IpSubnetFilterRule(IPv6.WILDCARD, 0))

            client.onConnect {
                clientConnectSuccess.value = true
            }

            client.onDisconnect {
                println("**************************** CLOSE")
                stopEndPoints()
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
            server.bind()
            server.filter(IpSubnetFilterRule("1.1.1.1", 32)) // this address will NEVER actually connect. we just use it for testing

            server.onConnect {
                close()
            }
        }

        run {
            val config = clientConfig()

            val client: Client<Connection> = Client(config)
            addEndPoint(client)

            client.onDisconnect {
                stopEndPoints()
            }

            try {
                client.connect(LOCALHOST)
            } catch (e: Exception) {
                e.printStackTrace()
                stopEndPoints()
                throw e
            }
        }

        waitForThreads()
    }

    @Test
    fun rejectServerIpc() {
        val serverConnectSuccess = atomic(false)
        val clientConnectSuccess = atomic(false)

        run {
            val configuration = serverConfig() {
                enableIpc = true
            }

            val server: Server<Connection> = Server(configuration)
            addEndPoint(server)
            server.bind()
            server.filter(IpSubnetFilterRule("1.1.1.1", 32)) // this address will NEVER actually connect. we just use it for testing

            server.onConnect {
                serverConnectSuccess.lazySet(true)
                close()
            }
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
                stopEndPoints()
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

        Assert.assertFalse(serverConnectSuccess.value)
        Assert.assertFalse(clientConnectSuccess.value)
    }

    @Test(expected = ClientException::class)
    fun rejectClient() {
        run {
            val configuration = serverConfig()

            val server: Server<Connection> = Server(configuration)
            addEndPoint(server)
            server.bind()

            server.onConnect {
                close()
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
            server.bind()
            server.filter {
                true
            }

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
                stopEndPoints()
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
            server.bind()

            server.onConnect {
                serverConnectSuccess.value = true
                logger.error { "closing" }
                close()
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
                logger.error { "on close" }
                stopEndPoints()
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
                stopEndPoints()
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
            server.bind()

            server.onConnect {
                close()
            }
        }

        run {
            val config = clientConfig()

            val client: Client<Connection> = Client(config)
            addEndPoint(client)
            client.filter {
                false
            }

            client.onDisconnect {
                stopEndPoints()
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
