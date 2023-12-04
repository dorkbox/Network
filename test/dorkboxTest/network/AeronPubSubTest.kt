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

import dorkbox.collections.LockFreeArrayList
import dorkbox.network.Configuration
import dorkbox.network.aeron.AeronDriver
import dorkbox.network.aeron.endpoint
import dorkbox.network.exceptions.ClientTimedOutException
import dorkbox.util.NamedThreadFactory
import io.aeron.CommonContext
import io.aeron.Publication
import kotlinx.atomicfu.atomic
import org.junit.Assert
import org.junit.Test
import org.slf4j.LoggerFactory
import java.util.concurrent.*

class AeronPubSubTest : BaseTest() {
    @Test
    fun connectTestNormalPub() {
        val log = LoggerFactory.getLogger("ConnectTest")

        // NOTE: once a config is assigned to a driver, the config cannot be changed
        val totalCount = 40
        val port = 3535
        val serverStreamId = 55555
        val handshakeTimeoutNs = TimeUnit.SECONDS.toNanos(10)



        val serverDriver = run {
            val conf = serverConfig()
            conf.enableIPv6 = false
            conf.uniqueAeronDirectory = true

            val driver = AeronDriver(conf, log, null)
            driver.start()
            driver
        }

        val clientDrivers = mutableListOf<AeronDriver>()
        val clientPublications = mutableListOf<Pair<AeronDriver, Publication>>()


        for (i in 1..totalCount) {
            val conf = clientConfig()
            conf.enableIPv6 = false
            conf.uniqueAeronDirectory = true

            val driver = AeronDriver(conf, log, null)
            driver.start()

            clientDrivers.add(driver)
        }



        val subscriptionUri = AeronDriver.uriHandshake(CommonContext.UDP_MEDIA, true)
            .endpoint(true, "127.0.0.1", port)
        val sub = serverDriver.addSubscription(subscriptionUri, serverStreamId, "server", false)

        var sessionID = 1234567
        clientDrivers.forEachIndexed { index, clientDriver ->
            val publicationUri = AeronDriver.uri(CommonContext.UDP_MEDIA, sessionID++, true)
                .endpoint(true, "127.0.0.1", port)

            // can throw an exception! We catch it in the calling class
            val publication = clientDriver.addPublication(publicationUri, serverStreamId, "client_$index", false)

            // can throw an exception! We catch it in the calling class
            // we actually have to wait for it to connect before we continue
            clientDriver.waitForConnection(publication, handshakeTimeoutNs, "client_$index") { cause ->
                ClientTimedOutException("Client publication cannot connect with localhost server", cause)
            }

            clientPublications.add(Pair(clientDriver, publication))
        }


        clientPublications.forEachIndexed { index, (clientDriver, pub) ->
            clientDriver.close(pub, "client_$index")
        }

        serverDriver.close(sub, "server")


        clientDrivers.forEach { clientDriver ->
            clientDriver.close()
        }

        clientDrivers.forEach { clientDriver ->
            clientDriver.ensureStopped(10_000, 500)
        }

        serverDriver.close()
        serverDriver.ensureStopped(10_000, 500)

        // have to make sure that the aeron driver is CLOSED.
        Assert.assertTrue("The aeron drivers are not fully closed!", AeronDriver.areAllInstancesClosed())
    }

    @Test
    fun connectTestExclusivePub() {
        val log = LoggerFactory.getLogger("ConnectTest")

        // NOTE: once a config is assigned to a driver, the config cannot be changed
        val totalCount = 40
        val port = 3535
        val serverStreamId = 55555
        val handshakeTimeoutNs = TimeUnit.SECONDS.toNanos(10)



        val serverDriver = run {
            val conf = serverConfig()
            conf.enableIPv6 = false
            conf.uniqueAeronDirectory = true

            val driver = AeronDriver(conf, log, null)
            driver.start()
            driver
        }

        val clientDrivers = mutableListOf<AeronDriver>()
        val clientPublications = mutableListOf<Pair<AeronDriver, Publication>>()


        for (i in 1..totalCount) {
            val conf = clientConfig()
            conf.enableIPv6 = false
            conf.uniqueAeronDirectory = true

            val driver = AeronDriver(conf, log, null)
            driver.start()

            clientDrivers.add(driver)
        }



        val subscriptionUri = AeronDriver.uriHandshake(CommonContext.UDP_MEDIA, true)
            .endpoint(true, "127.0.0.1", port)
        val sub = serverDriver.addSubscription(subscriptionUri, serverStreamId, "server", false)

        var sessionID = 1234567
        clientDrivers.forEachIndexed { index, clientDriver ->
            val publicationUri = AeronDriver.uri(CommonContext.UDP_MEDIA, sessionID++, true)
                .endpoint(true, "127.0.0.1", port)

            // can throw an exception! We catch it in the calling class
            val publication = clientDriver.addExclusivePublication(publicationUri, serverStreamId, "client_$index", false)

            // can throw an exception! We catch it in the calling class
            // we actually have to wait for it to connect before we continue
            clientDriver.waitForConnection(publication, handshakeTimeoutNs, "client_$index") { cause ->
                ClientTimedOutException("Client publication cannot connect with localhost server", cause)
            }

            clientPublications.add(Pair(clientDriver, publication))
        }


        clientPublications.forEachIndexed { index, (clientDriver, pub) ->
            clientDriver.close(pub, "client_$index")
        }

        serverDriver.close(sub, "server")


        clientDrivers.forEach { clientDriver ->
            clientDriver.close()
        }

        clientDrivers.forEach { clientDriver ->
            clientDriver.ensureStopped(10_000, 500)
        }

        serverDriver.close()
        serverDriver.ensureStopped(10_000, 500)

        // have to make sure that the aeron driver is CLOSED.
        Assert.assertTrue("The aeron drivers are not fully closed!", AeronDriver.areAllInstancesClosed())
    }

    @Test
    fun reconnectTest() {
        val log = LoggerFactory.getLogger("ConnectTest")

        // NOTE: once a config is assigned to a driver, the config cannot be changed
        val totalCount = 40
        val port = 3535
        val serverStreamId = 55555
        val handshakeTimeoutNs = TimeUnit.SECONDS.toNanos(10)



        val serverDriver = run {
            val conf = serverConfig()
            conf.enableIPv6 = false
            conf.uniqueAeronDirectory = true

            val driver = AeronDriver(conf, log, null)
            driver.start()
            driver
        }

        val clientDrivers = mutableListOf<AeronDriver>()
        val clientPublications = mutableListOf<Pair<AeronDriver, Publication>>()


        for (i in 1..totalCount) {
            val conf = clientConfig()
            conf.enableIPv6 = false
            conf.uniqueAeronDirectory = true

            val driver = AeronDriver(conf, log, null)
            driver.start()

            clientDrivers.add(driver)
        }



        val subscriptionUri = AeronDriver.uriHandshake(CommonContext.UDP_MEDIA, true)
            .endpoint(true, "127.0.0.1", port)
        val sub = serverDriver.addSubscription(subscriptionUri, serverStreamId, "server", false)

        var sessionID = 1234567
        clientDrivers.forEachIndexed { index, clientDriver ->
            val publicationUri = AeronDriver.uri(CommonContext.UDP_MEDIA, sessionID++, true)
                .endpoint(true, "127.0.0.1", port)

            // can throw an exception! We catch it in the calling class
            val publication = clientDriver.addPublication(publicationUri, serverStreamId, "client_$index", false)

            // can throw an exception! We catch it in the calling class
            // we actually have to wait for it to connect before we continue
            clientDriver.waitForConnection(publication, handshakeTimeoutNs, "client_$index") { cause ->
                ClientTimedOutException("Client publication cannot connect with localhost server", cause)
            }

            clientPublications.add(Pair(clientDriver, publication))
        }


        clientPublications.forEachIndexed { index, (clientDriver, pub) ->
            clientDriver.close(pub, "client_$index")
        }

        println("reconnecting..")

        // THE RECONNECT PART
        clientDrivers.forEachIndexed { index, clientDriver ->
            val publicationUri = AeronDriver.uri(CommonContext.UDP_MEDIA, sessionID++, true)
                .endpoint(true, "127.0.0.1", port)

            // can throw an exception! We catch it in the calling class
            val publication = clientDriver.addPublication(publicationUri, serverStreamId, "client_$index", false)

            // can throw an exception! We catch it in the calling class
            // we actually have to wait for it to connect before we continue
            clientDriver.waitForConnection(publication, handshakeTimeoutNs, "client_$index") { cause ->
                ClientTimedOutException("Client publication cannot connect with localhost server", cause)
            }

            clientPublications.add(Pair(clientDriver, publication))
        }

        clientPublications.forEachIndexed { index, (clientDriver, pub) ->
            clientDriver.close(pub, "client_$index")
        }


        println("Closing..")

        clientDrivers.forEach { clientDriver ->
            clientDriver.close()
        }

        serverDriver.close(sub, "server")

        clientDrivers.forEach { clientDriver ->
            clientDriver.ensureStopped(10_000, 500)
        }

        serverDriver.close()
        serverDriver.ensureStopped(10_000, 500)

        // have to make sure that the aeron driver is CLOSED.
        Assert.assertTrue("The aeron drivers are not fully closed!", AeronDriver.areAllInstancesClosed())
    }

    @Test
    fun reconnectMultiTest() {
        val log = LoggerFactory.getLogger("ConnectTest")

        // NOTE: once a config is assigned to a driver, the config cannot be changed
        val totalCount = 80
        val port = 3535
        val serverStreamId = 55555
        val handshakeTimeoutNs = TimeUnit.SECONDS.toNanos(10)



        val serverDriver = run {
            val conf = serverConfig()
            conf.enableIPv6 = false
            conf.uniqueAeronDirectory = true

            val driver = AeronDriver(conf, log, null)
            driver.start()
            driver
        }

        val clientDrivers = mutableListOf<AeronDriver>()
        val clientPublications = LockFreeArrayList<Pair<AeronDriver, Publication>>()


        for (i in 1..totalCount) {
            val conf = clientConfig()
            conf.enableIPv6 = false
            conf.uniqueAeronDirectory = true

            val driver = AeronDriver(conf, log, null)
            driver.start()

            clientDrivers.add(driver)
        }



        val subscriptionUri = AeronDriver.uriHandshake(CommonContext.UDP_MEDIA, true)
            .endpoint(true, "127.0.0.1", port)
        val sub = serverDriver.addSubscription(subscriptionUri, serverStreamId, "server", false)

        val sessionID = atomic(1234567)


        // if we are on the same JVM, the defaultScope for coroutines is SHARED, and limited!
        val differentThreadLaunchers = Executors.newFixedThreadPool(totalCount/2,
            NamedThreadFactory("Unit Test Client", Configuration.networkThreadGroup, true)
        )

        var latch = CountDownLatch(clientDrivers.size)

        clientDrivers.forEachIndexed { index, clientDriver ->
            differentThreadLaunchers.submit {
                val publicationUri = AeronDriver.uri(CommonContext.UDP_MEDIA, sessionID.getAndIncrement(), true).endpoint(true, "127.0.0.1", port)

                // can throw an exception! We catch it in the calling class
                val publication = clientDriver.addPublication(publicationUri, serverStreamId, "client_$index", false)

                // can throw an exception! We catch it in the calling class
                // we actually have to wait for it to connect before we continue
                clientDriver.waitForConnection(publication, handshakeTimeoutNs, "client_$index") { cause ->
                    ClientTimedOutException("Client publication cannot connect with localhost server", cause)
                }

                clientPublications.add(Pair(clientDriver, publication))
                latch.countDown()
            }
        }

        latch.await()

        clientPublications.forEachIndexed { index, (clientDriver, pub) ->
            clientDriver.close(pub, "client_$index")
        }

        println("reconnecting..")
        latch = CountDownLatch(clientDrivers.size)

        // THE RECONNECT PART
        clientDrivers.forEachIndexed { index, clientDriver ->
            differentThreadLaunchers.submit {
                val publicationUri = AeronDriver.uri(CommonContext.UDP_MEDIA, sessionID.getAndIncrement(), true).endpoint(true, "127.0.0.1", port)

                // can throw an exception! We catch it in the calling class
                val publication = clientDriver.addPublication(publicationUri, serverStreamId, "client_$index", false)

                // can throw an exception! We catch it in the calling class
                // we actually have to wait for it to connect before we continue
                clientDriver.waitForConnection(publication, handshakeTimeoutNs, "client_$index") { cause ->
                    ClientTimedOutException("Client publication cannot connect with localhost server", cause)
                }

                clientPublications.add(Pair(clientDriver, publication))
                latch.countDown()
            }
        }
        latch.await()


        clientPublications.forEachIndexed { index, (clientDriver, pub) ->
            clientDriver.close(pub, "client_$index")
        }


        println("Closing..")

        clientDrivers.forEach { clientDriver ->
            clientDriver.close()
        }

        serverDriver.close(sub, "server")

        clientDrivers.forEach { clientDriver ->
            clientDriver.ensureStopped(10_000, 500)
        }

        serverDriver.close()
        serverDriver.ensureStopped(10_000, 500)

        // have to make sure that the aeron driver is CLOSED.
        Assert.assertTrue("The aeron drivers are not fully closed!", AeronDriver.areAllInstancesClosed())
    }



    @Test()
    fun connectFailWithBadSessionIdTest() {
        val log = LoggerFactory.getLogger("ConnectTest")

        // NOTE: once a config is assigned to a driver, the config cannot be changed
        val totalCount = 40
        val port = 3535
        val serverStreamId = 55555
        val handshakeTimeoutNs = TimeUnit.SECONDS.toNanos(10)



        val serverDriver = run {
            val conf = serverConfig()
            conf.enableIPv6 = false
            conf.uniqueAeronDirectory = true

            val driver = AeronDriver(conf, log, null)
            driver.start()
            driver
        }

        val clientDrivers = mutableListOf<AeronDriver>()
        val clientPublications = mutableListOf<Pair<AeronDriver, Publication>>()


        for (i in 1..totalCount) {
            val conf = clientConfig()
            conf.enableIPv6 = false
            conf.uniqueAeronDirectory = true

            val driver = AeronDriver(conf, log, null)
            driver.start()

            clientDrivers.add(driver)
        }



        val subscriptionUri = AeronDriver.uriHandshake(CommonContext.UDP_MEDIA, true)
            .endpoint(true, "127.0.0.1", port)
        val sub = serverDriver.addSubscription(subscriptionUri, serverStreamId, "server", false)

        try {
            var sessionID = 1234567
            clientDrivers.forEachIndexed { index, clientDriver ->
                val publicationUri = AeronDriver.uri(CommonContext.UDP_MEDIA, sessionID, true)
                    .endpoint(true, "127.0.0.1", port)


                // can throw an exception! We catch it in the calling class
                val publication = clientDriver.addPublication(publicationUri, serverStreamId, "client_$index", false)

                // can throw an exception! We catch it in the calling class
                // we actually have to wait for it to connect before we continue
                clientDriver.waitForConnection(publication, handshakeTimeoutNs, "client_$index") { cause ->
                    ClientTimedOutException("Client publication cannot connect with localhost server", cause)
                }

                clientPublications.add(Pair(clientDriver, publication))
            }
            Assert.fail("TimeoutException should be caught!")
        } catch (ignore: Exception) {
        }


        clientPublications.forEachIndexed { index, (clientDriver, pub) ->
            clientDriver.close(pub, "client_$index")
        }

        serverDriver.close(sub, "server")


        clientDrivers.forEach { clientDriver ->
            clientDriver.close()
        }

        clientDrivers.forEach { clientDriver ->
            clientDriver.ensureStopped(10_000, 500)
        }

        serverDriver.close()
        serverDriver.ensureStopped(10_000, 500)

        // have to make sure that the aeron driver is CLOSED.
        Assert.assertTrue("The aeron drivers are not fully closed!", AeronDriver.areAllInstancesClosed())
    }
}
