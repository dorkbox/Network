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
import dorkbox.network.Client
import dorkbox.network.Server
import dorkbox.network.connection.Connection
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.*

class MultiClientTest : BaseTest() {
    private val totalCount = 2
    private val clientConnectCount = atomic(0)
    private val serverConnectCount = atomic(0)
    private val disconnectCount = atomic(0)

    @Test
    fun multiConnectClient() {
        // clients first, so they try to connect to the server at (roughly) the same time
        val clients = mutableListOf<Client<Connection>>()
        for (i in 1..totalCount) {
            val config = clientConfig()
            config.enableIPv6 = false
            config.uniqueAeronDirectory = true

            val client: Client<Connection> = Client(config, "Client$i")
            client.onConnect {
                val count = clientConnectCount.getAndIncrement()

                logger.error("${this.id} - Connected $count ($i)!")
            }
            client.onDisconnect {
                disconnectCount.getAndIncrement()
                logger.error("${this.id} - Disconnected $i!")
            }
            addEndPoint(client)
            clients += client
        }

        // start up the drivers first
        runBlocking {
            clients.forEach {
                it.startDriver()
            }
        }

        val configuration = serverConfig()
        configuration.enableIPv6 = false

        val server: Server<Connection> = Server(configuration)
        addEndPoint(server)
        server.onConnect {
            val count = serverConnectCount.incrementAndGet()

            logger.error("${this.id} - Connecting $count ....")
            close()

            if (count == totalCount) {
                logger.error { "Stopping endpoints!" }
//                delay(6000)
//                outputStats(server)
//
//                delay(2000)
//                outputStats(server)
//
//                delay(2000)
//                outputStats(server)

                stopEndPoints(10000L)
            }
        }

        server.bind()

        GlobalScope.launch {
            clients.forEach {
                // long connection timeout, since the more that try to connect at the same time, the longer it takes to setup aeron (since it's all shared)
                launch { it.connect(LOCALHOST, 300*totalCount) }
            }
        }

        waitForThreads() {
            outputStats(server)
        }


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
