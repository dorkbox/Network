/*
 * Copyright 2020 dorkbox, llc
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
 *
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
import dorkbox.network.Server
import dorkbox.network.connection.Connection
import dorkbox.util.exceptions.SecurityException
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class MultipleServerTest : BaseTest() {
    val total = 5
    var received = AtomicInteger()

    @Test
    @Throws(SecurityException::class, IOException::class)
    fun multipleServers() {
        val portOffset = 2

        var serverAeronDir: File? = null
        val didReceive = mutableListOf<AtomicBoolean>()

        for (count in 0 until total) {
            didReceive.add(AtomicBoolean())
            val offset = count * portOffset

            val configuration = serverConfig()
            configuration.subscriptionPort += offset
            configuration.publicationPort += offset
            configuration.aeronLogDirectory = serverAeronDir

            val server: Server<Connection> = Server(configuration)
            addEndPoint(server)

            server.onMessage<String>{ connection, message ->
                if (message != "client_$count") {
                    Assert.fail()
                }

                didReceive[count].set(true)
                if (received.incrementAndGet() == total) {
                    connection.logger.error("Done, stopping endpoints")
                    stopEndPoints()
                }
            }

            server.bind()

            serverAeronDir = File(configuration.aeronLogDirectory.toString() + count)
        }

        var clientAeronDir: File? = null
        val didSend = mutableListOf<AtomicBoolean>()

        for (count in 0 until total) {
            didSend.add(AtomicBoolean())
            val offset = count * portOffset

            val configuration = clientConfig()
            configuration.subscriptionPort += offset
            configuration.publicationPort += offset
            configuration.aeronLogDirectory = clientAeronDir

            val client: Client<Connection> = Client(configuration)
            addEndPoint(client)

            clientAeronDir = File(configuration.aeronLogDirectory.toString() + count)

            client.onConnect { connection ->
                didSend[count].set(true)
                connection.send("client_$count")
            }

            runBlocking {
                client.connect(LOOPBACK)
            }
        }

        waitForThreads(30)

        didSend.forEach {
            assertTrue(it.get())
        }
        didReceive.forEach {
            assertTrue(it.get())
        }
    }
}
