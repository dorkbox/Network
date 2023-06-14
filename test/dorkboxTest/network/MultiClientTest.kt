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
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.*

@Suppress("UNUSED_ANONYMOUS_PARAMETER")
class MultiClientTest : BaseTest() {
    private val totalCount = 80

    private val clientConnectCount = atomic(0)
    private val serverConnectCount = atomic(0)
    private val disconnectCount = atomic(0)

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun multiConnectClient() {
        val server = run {
            val configuration = serverConfig()
            configuration.enableIPv6 = false
            configuration.uniqueAeronDirectory = true

            val server: Server<Connection> = Server(configuration)
            addEndPoint(server)
            server.onConnect {
                val count = serverConnectCount.incrementAndGet()

                logger.error("${this.id} - Connected $count ....")
                close()

                if (count == totalCount) {
                    logger.error { "Stopping endpoints!" }

                    // waiting just a few so that we can make sure that the handshake messages are properly sent
                    // if we DO NOT wait, what will happen is that the client will CLOSE before it receives the handshake HELLO_ACK
//                    delay(500)

                    stopEndPointsSuspending()
                }
            }

            server.bind()
            server
        }


        println()
        println()
        println()
        println()



        // clients first, so they try to connect to the server at (roughly) the same time
        val clients = mutableListOf<Client<Connection>>()
        for (i in 1..totalCount) {
            val config = clientConfig()
            config.enableIPv6 = false
            config.uniqueAeronDirectory = true

            val client: Client<Connection> = Client(config, "Client $i")
            client.onConnect {
                val count = clientConnectCount.incrementAndGet()
                logger.error("$id - Connected $count ($i)!")
            }

            client.onDisconnect {
                val count = disconnectCount.incrementAndGet()
                logger.error("$id - Disconnected $count ($i)!")
            }

            addEndPoint(client)
            clients += client
        }

        // start up the drivers first
        runBlocking {
            clients.forEach {
                println("******************")
                it.startDriver()
                println("******************")
            }
        }


        clients.forEach {
            GlobalScope.launch {
                // long connection timeout, since the more that try to connect at the same time, the longer it takes to setup aeron (since it's all shared)
                it.connect(LOCALHOST, 30)
            }
        }

        waitForThreads(totalCount*AUTO_FAIL_TIMEOUT) {
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
