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
/*
 * Copyright (c) 2008, Nathan Sweet
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following
 * conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided with the distribution.
 * - Neither the name of Esoteric Software nor the names of its contributors may be used to endorse or promote products derived
 * from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING,
 * BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT
 * SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package dorkboxTest.network

import dorkbox.network.Client
import dorkbox.network.Configuration
import dorkbox.network.Server
import dorkbox.network.connection.Connection
import dorkbox.network.exceptions.ServerException
import dorkbox.util.exceptions.SecurityException
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.*

class MultipleServerTest : BaseTest() {
    private val total = 4


    @Test
    @Throws(SecurityException::class, IOException::class)
    fun multipleUDP() {
        val received = AtomicInteger(0)

        var serverAeronDir: File? = null
        val didReceive = mutableListOf<AtomicBoolean>()
        val servers = mutableListOf<Server<Connection>>()

        for (count in 0 until total) {
            didReceive.add(AtomicBoolean())

            val configuration = serverConfig()
            configuration.aeronDirectory = serverAeronDir
            configuration.enableIpc = false

            val server: Server<Connection> = Server(configuration)
            addEndPoint(server)

            server.onMessage<String>{ message ->
                if (message != "client_$count") {
                    Assert.fail()
                }

                didReceive[count].set(true)
                if (received.incrementAndGet() == total) {
                    logger.error("Done, stopping endpoints")
                    stopEndPoints()
                }
            }

            servers.add(server)

            serverAeronDir = File(configuration.aeronDirectory.toString() + count)
        }

        var clientAeronDir: File? = null
        val didSend = mutableListOf<AtomicBoolean>()
        val clients = mutableListOf<Client<Connection>>()

        for (count in 0 until total) {
            didSend.add(AtomicBoolean())
            val configuration = clientConfig()
            configuration.aeronDirectory = clientAeronDir
            configuration.enableIpc = false


            val client: Client<Connection> = Client(configuration)
            addEndPoint(client)

            clientAeronDir = File(configuration.aeronDirectory.toString() + count)

            client.onConnect {
                didSend[count].set(true)
                send("client_$count")
            }

            clients.add(client)
        }

        for (count in 0 until total) {
            servers[count].bind(2000 + count)
        }

        for (count in 0 until total) {
            clients[count].connect(LOCALHOST, 2000+count)
        }

        waitForThreads()

        didSend.forEach {
            assertTrue(it.get())
        }
        didReceive.forEach {
            assertTrue(it.get())
        }
    }

    @Test(expected = ServerException::class)
    @Throws(SecurityException::class, IOException::class)
    fun multipleInvalidIPC() {
        val servers = mutableListOf<Server<Connection>>()
        try {
            for (count in 0 until total) {
                val configuration = serverConfig()
                configuration.enableIPv4 = true
                configuration.enableIPv6 = true
                configuration.enableIpc = true

                servers.add(Server(configuration, "server_$count"))
            }
        } catch (e: Exception) {
            runBlocking {
                servers.forEach {
                    it.close()
                    it.waitForClose()
                }
            }
            throw e
        }
    }

    @Test
    @Throws(SecurityException::class, IOException::class)
    fun multipleIPC() {
        val received = AtomicInteger(0)

        // client and server must share locations
        val aeronDirs = mutableListOf<File>()
        for (count in 0 until total) {
            val baseFileLocation = Configuration.defaultAeronLogLocation()
            aeronDirs.add(File(baseFileLocation, "aeron_${count}"))
        }


        val didReceive = mutableListOf<AtomicBoolean>()
        val servers = mutableListOf<Server<Connection>>()

        for (count in 0 until total) {
            didReceive.add(AtomicBoolean())

            val configuration = serverConfig()
            configuration.aeronDirectory = aeronDirs[count]
            configuration.enableIPv4 = false
            configuration.enableIPv6 = false
            configuration.enableIpc = true

            val server: Server<Connection> = Server(configuration, "server_$count")
            addEndPoint(server)

            server.onInit {
                logger.warn { "INIT: $count" }
            }

            server.onConnect {
                logger.warn { "CONNECT: $count" }
            }

            server.onMessage<String> { message ->
                assertEquals(message, "client_$count")

                didReceive[count].set(true)
                if (received.incrementAndGet() == total) {
                    logger.error("Done, stopping endpoints")
                    stopEndPoints()
                }
            }

            servers.add(server)
        }

        val didSend = mutableListOf<AtomicBoolean>()
        val clients = mutableListOf<Client<Connection>>()

        for (count in 0 until total) {
            didSend.add(AtomicBoolean())

            val configuration = clientConfig()
            configuration.aeronDirectory = aeronDirs[count]
            configuration.enableIPv4 = false
            configuration.enableIPv6 = false
            configuration.enableIpc = true

            val client: Client<Connection> = Client(configuration, "client_$count")
            addEndPoint(client)

            client.onInit {
                logger.warn { "INIT: $count" }
            }

            client.onConnect {
                logger.warn { "CONNECT: $count" }
                didSend[count].set(true)
                send("client_$count")
            }

            clients.add(client)
        }

        for (count in 0 until total) {
            servers[count].bind(2000+count)
        }


        for (count in 0 until total) {
            clients[count].connect(LOCALHOST, 2000+count)
        }

        waitForThreads()

        didSend.forEach {
            assertTrue(it.get())
        }
        didReceive.forEach {
            assertTrue(it.get())
        }
    }
}
