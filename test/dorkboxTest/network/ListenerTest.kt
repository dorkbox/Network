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
import dorkbox.network.connection.ConnectionParams
import dorkbox.util.exceptions.InitializationException
import dorkbox.util.exceptions.SecurityException
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ListenerTest : BaseTest() {
    private val origString = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" // lots of a's to encourage compression
    private val limit = 20
    private val count = AtomicInteger(0)
    var checkFail1 = AtomicBoolean(false)
    var checkFail2 = AtomicBoolean(false)

    var overrideCheck = AtomicBoolean(false)
    var serverOnMessage = AtomicBoolean(false)
    var serverConnectionOnMessage = AtomicBoolean(false)
    var serverDisconnectMessage = AtomicBoolean(false)
    var serverConnect = AtomicBoolean(false)
    var serverDisconnect = AtomicBoolean(false)
    var clientConnect = AtomicBoolean(false)
    var clientDisconnect = AtomicBoolean(false)

    // quick and dirty test to also test connection sub-classing
    internal open inner class TestConnectionA(connectionParameters: ConnectionParams<TestConnectionA>) : Connection(connectionParameters) {
        open fun check() {
            overrideCheck.set(true)
        }
    }

    @Test
    @Throws(SecurityException::class, InitializationException::class, IOException::class, InterruptedException::class)
    fun listener() {
        val server: Server<TestConnectionA> = object : Server<TestConnectionA>(
                serverConfig()) {
            override fun newConnection(connectionParameters: ConnectionParams<TestConnectionA>): TestConnectionA {
                return TestConnectionA(connectionParameters)
            }
        }
        addEndPoint(server)

        // standard listener
        server.onMessage<String> { connection, message ->
            // should be called
            connection.check()
            connection.send(message)
        }

        // generic listener
        server.onMessage<Any> { _, _ ->
            // should be called!
            serverOnMessage.set(true)
        }

        // standard connect check
        server.onConnect { connection ->
            serverConnect.set(true)
            connection.onMessage<Any> {_, _ ->
                serverConnectionOnMessage.set(true)
            }

            connection.onDisconnect { _ ->
                serverDisconnectMessage.set(true)
            }
        }

        // standard listener disconnect check
        server.onDisconnect {
            serverDisconnect.set(true)
        }


        runBlocking {
            server.bind(false)
        }




        // ----
        val client: Client<TestConnectionA> = object : Client<TestConnectionA>(
                clientConfig()) {
            override fun newConnection(connectionParameters: ConnectionParams<TestConnectionA>): TestConnectionA {
                return TestConnectionA(connectionParameters)
            }
        }
        addEndPoint(client)


        client.onConnect { connection ->
            connection.send(origString) // 20 a's
        }

        client.onMessage<String> { connection, message ->
            if (origString != message) {
                checkFail2.set(true)
                System.err.println("original string not equal to the string received")
                stopEndPoints()
                return@onMessage
            }

            if (count.getAndIncrement() < limit) {
                connection.send(message)
            } else {
                stopEndPoints()
            }
        }

        // standard connect check
        client.onConnect {
            clientConnect.set(true)
        }


        // standard listener disconnect check
        client.onDisconnect {
            clientDisconnect.set(true)
        }


        runBlocking {
            client.connect(LOOPBACK)
        }

        waitForThreads()

        // -1 BECAUSE we are `getAndIncrement` for each check earlier
        Assert.assertEquals(limit.toLong(), count.get() - 1.toLong())
        Assert.assertTrue(overrideCheck.get())
        Assert.assertTrue(serverOnMessage.get())
        Assert.assertTrue(serverConnectionOnMessage.get())
        Assert.assertTrue(serverDisconnectMessage.get())
        Assert.assertTrue(serverConnect.get())
        Assert.assertTrue(serverDisconnect.get())
        Assert.assertTrue(clientConnect.get())
        Assert.assertTrue(clientDisconnect.get())

        Assert.assertFalse(checkFail1.get())
        Assert.assertFalse(checkFail2.get())
    }
}
