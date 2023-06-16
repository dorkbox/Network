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

import dorkbox.network.aeron.AeronDriver
import dorkbox.network.aeron.endpoint
import dorkbox.network.exceptions.ClientTimedOutException
import io.aeron.Publication
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.junit.Assert
import org.junit.Test

class AeronPubSubTest : BaseTest() {
    @Test
    fun connectTest() {
        runBlocking {
            val log = KotlinLogging.logger("ConnectTest")

            // NOTE: once a config is assigned to a driver, the config cannot be changed
            val totalCount = 40
            val port = 3535
            val serverStreamId = 55555
            val handshakeTimeoutSec = 10



            val serverDriver = run {
                val conf = serverConfig()
                conf.enableIPv6 = false
                conf.uniqueAeronDirectory = true

                val driver = AeronDriver(conf, log)
                driver.start()
                driver
            }

            val clientDrivers = mutableListOf<AeronDriver>()
            val clientPublications = mutableListOf<Pair<AeronDriver, Publication>>()


            for (i in 1..totalCount) {
                val conf = clientConfig()
                conf.enableIPv6 = false
                conf.uniqueAeronDirectory = true

                val driver = AeronDriver(conf, log)
                driver.start()

                clientDrivers.add(driver)
            }



            val subscriptionUri = AeronDriver.uriHandshake("udp", true).endpoint(true, "127.0.0.1", port)
            val sub = serverDriver.addSubscription(subscriptionUri, serverStreamId, "server")

            var sessionID = 1234567
            clientDrivers.forEachIndexed { index, clientDriver ->
                val publicationUri = AeronDriver.uri("udp", sessionID++, true).endpoint(true, "127.0.0.1", port)
                clientDriver.addPublicationWithTimeout(publicationUri, handshakeTimeoutSec, serverStreamId, "client_$index") { cause ->
                    ClientTimedOutException("Client publication cannot connect with localhost server", cause)
                }.also {
                    clientPublications.add(Pair(clientDriver, it))
                }
            }


            clientPublications.forEachIndexed { index, (clientDriver, pub) ->
                clientDriver.closeAndDeletePublication(pub, "client_$index")
            }

            serverDriver.closeAndDeleteSubscription(sub, "server")


            clientDrivers.forEach { clientDriver ->
                clientDriver.close()
            }

            clientDrivers.forEach { clientDriver ->
                clientDriver.ensureStopped(10_000, 500)
            }

            serverDriver.close()
            serverDriver.ensureStopped(10_000, 500)
        }
    }

    @Test()
    fun connectFailWithBadSessionIdTest() {
        runBlocking {
            val log = KotlinLogging.logger("ConnectTest")

            // NOTE: once a config is assigned to a driver, the config cannot be changed
            val totalCount = 40
            val port = 3535
            val serverStreamId = 55555
            val handshakeTimeoutSec = 10



            val serverDriver = run {
                val conf = serverConfig()
                conf.enableIPv6 = false
                conf.uniqueAeronDirectory = true

                val driver = AeronDriver(conf, log)
                driver.start()
                driver
            }

            val clientDrivers = mutableListOf<AeronDriver>()
            val clientPublications = mutableListOf<Pair<AeronDriver, Publication>>()


            for (i in 1..totalCount) {
                val conf = clientConfig()
                conf.enableIPv6 = false
                conf.uniqueAeronDirectory = true

                val driver = AeronDriver(conf, log)
                driver.start()

                clientDrivers.add(driver)
            }



            val subscriptionUri = AeronDriver.uriHandshake("udp", true).endpoint(true, "127.0.0.1", port)
            val sub = serverDriver.addSubscription(subscriptionUri, serverStreamId, "server")

            try {
                var sessionID = 1234567
                clientDrivers.forEachIndexed { index, clientDriver ->
                    val publicationUri = AeronDriver.uri("udp", sessionID, true).endpoint(true, "127.0.0.1", port)
                    clientDriver.addPublicationWithTimeout(publicationUri, handshakeTimeoutSec, serverStreamId, "client_$index") { cause ->
                        ClientTimedOutException("Client publication cannot connect with localhost server", cause)
                    }.also {
                        clientPublications.add(Pair(clientDriver, it))
                    }
                }
                Assert.fail("TimeoutException should be caught!")
            } catch (ignore: Exception) {
            }


            clientPublications.forEachIndexed { index, (clientDriver, pub) ->
                clientDriver.closeAndDeletePublication(pub, "client_$index")
            }

            serverDriver.closeAndDeleteSubscription(sub, "server")


            clientDrivers.forEach { clientDriver ->
                clientDriver.close()
            }

            clientDrivers.forEach { clientDriver ->
                clientDriver.ensureStopped(10_000, 500)
            }

            serverDriver.close()
            serverDriver.ensureStopped(10_000, 500)
        }
    }
}
