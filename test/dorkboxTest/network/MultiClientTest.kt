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
import dorkbox.network.Configuration
import dorkbox.network.Server
import dorkbox.network.connection.Connection
import dorkbox.util.NamedThreadFactory
import io.aeron.driver.ThreadingMode
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.*

@Suppress("UNUSED_ANONYMOUS_PARAMETER")
class MultiClientTest : BaseTest() {
    private val totalCount = 100

    private val clientConnectCount = atomic(0)
    private val serverConnectCount = atomic(0)
    private val disconnectCount = atomic(0)

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    @Test
    fun multiConnectClient() {
        val server = run {
            val config = serverConfig()
            config.uniqueAeronDirectory = true
            config.threadingMode = ThreadingMode.DEDICATED

            val server: Server<Connection> = Server(config)
            addEndPoint(server)
            server.onConnect {
                val count = serverConnectCount.incrementAndGet()

                logger.error("${this.id} - Connected $count ....")
                close()
            }
            server
        }


        println()
        println()
        println()
        println()

        val shutdownLatch = CountDownLatch(totalCount)


        // clients first, so they try to connect to the server at (roughly) the same time
        val clients = mutableListOf<Client<Connection>>()
        for (i in 1..totalCount) {
            val config = clientConfig()
            config.uniqueAeronDirectory = true

            val client: Client<Connection> = Client(config, "Client $i")
            client.onInit {
                val count = clientConnectCount.incrementAndGet()
                logger.error("$id - Connected $count ($i)!")
            }

            client.onDisconnect {
                val count = disconnectCount.incrementAndGet()
                logger.error("$id - Disconnected $count ($i)!")
                shutdownLatch.countDown()
            }

            addEndPoint(client)
            clients += client
        }


        server.bind(2000, 2001)

        // start up the drivers first
        clients.forEach {
            it.startDriver()
        }

        // if we are on the same JVM, the defaultScope for coroutines is SHARED, and limited!
        val differentThreadLaunchers = Executors.newFixedThreadPool(totalCount/2,
            NamedThreadFactory("Unit Test Client", Configuration.networkThreadGroup, true)
        )

        clients.forEachIndexed { count, client ->
            differentThreadLaunchers.submit {
                client.connect(LOCALHOST, 2000, 2001, 30)
            }
        }

        shutdownLatch.await()
        stopEndPoints()
        waitForThreads()

        Assert.assertEquals(totalCount, clientConnectCount.value)
        Assert.assertEquals(totalCount, serverConnectCount.value)
        Assert.assertEquals(totalCount, disconnectCount.value)
    }

    fun outputStats(server: Server<Connection>) {
        val dateFormat = SimpleDateFormat("HH:mm:ss")
        print(dateFormat.format(Date()))
        println("======================================================================")
        server.driverCounters { counterId, counterValue, typeId, keyBuffer, label ->
            //if (counterFilter.filter(typeId, keyBuffer)) {
            System.out.format("%3d: %,20d - %s%n", counterId, counterValue, label)
            //}
        }

        println(server.driverBacklog()?.output())
    }
}
