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
import dorkbox.network.Server
import dorkbox.network.connection.Connection
import dorkbox.network.connection.ConnectionParams
import dorkbox.util.exceptions.InitializationException
import dorkbox.util.exceptions.SecurityException
import kotlinx.atomicfu.atomic
import org.junit.Assert
import org.junit.Test
import java.io.IOException

class ListenerTest : BaseTest() {
    private val origString = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val limit = 20

    private val count = atomic(0)
    var checkFail1 = atomic(false)
    var checkFail2 = atomic(false)

    var overrideCheck = atomic(false)
    var serverOnMessage = atomic(false)

    var serverConnectionOnMessage = atomic(0)
    var serverDisconnectMessage = atomic(0)
    var serverConnect = atomic(false)
    var serverDisconnect = atomic(false)
    var clientConnect = atomic(false)
    var clientDisconnect = atomic(false)

    // quick and dirty test to also test connection sub-classing
    internal open inner class TestConnectionA(connectionParameters: ConnectionParams<TestConnectionA>) : Connection(connectionParameters) {
        open fun check() {
            overrideCheck.lazySet(true)
        }
    }

    @Test
    @Throws(SecurityException::class, InitializationException::class, IOException::class, InterruptedException::class)
    fun listener() {
        val server = object : Server<TestConnectionA>(serverConfig()) {
            override fun newConnection(connectionParameters: ConnectionParams<TestConnectionA>): TestConnectionA {
                return TestConnectionA(connectionParameters)
            }
        }

        addEndPoint(server)

        // has session/stream count errors!
        // standard listener
        server.onMessage<String> { message ->
            logger.error ("server string message")
            // should be called
            check()
            send(message)
        }

        // generic listener
        server.onMessage<Any> {
            // should be called!
            serverOnMessage.lazySet(true)
            logger.error ("server any message")
        }

        // standard connect check
        server.onConnect {
            logger.error ("server connect")
            serverConnect.lazySet(true)

            onMessage<Any> {
                logger.error ("server connection any message")
                serverConnectionOnMessage.getAndIncrement()
            }

            onDisconnect {
                logger.error ("server connection disconnect")
                serverDisconnectMessage.getAndIncrement()
            }
        }

        // standard listener disconnect check
        server.onDisconnect {
            logger.error ("server disconnect")
            serverDisconnect.lazySet(true)
        }




        // ----
        val client = object : Client<TestConnectionA>(clientConfig()) {
            override fun newConnection(connectionParameters: ConnectionParams<TestConnectionA>): TestConnectionA {
                return TestConnectionA(connectionParameters)
            }
        }

        addEndPoint(client)


        client.onConnect {
            logger.error("client connect 1")
            send(origString) // 20 a's
        }

        // standard connect check
        client.onConnect {
            logger.error("client connect 2")
            clientConnect.lazySet(true)
        }


        client.onMessage<String> { message ->
            logger.error("client string message")
            if (origString != message) {
                checkFail2.lazySet(true)
                System.err.println("original string not equal to the string received")
                stopEndPoints()
                return@onMessage
            }

            if (count.getAndIncrement() < limit) {
                send(message)
            } else {
                stopEndPoints()
            }
        }

        // standard listener disconnect check
        client.onDisconnect {
            logger.error ("client disconnect")
            clientDisconnect.lazySet(true)
        }


        server.bind(2000)
        client.connect(LOCALHOST, 2000)

        waitForThreads()

        // +1 BECAUSE we are `getAndIncrement` for each check earlier
        val limitCheck = limit+1

        Assert.assertEquals(limitCheck, count.value)

        Assert.assertTrue(overrideCheck.value)
        Assert.assertTrue(serverOnMessage.value)
        Assert.assertEquals(limitCheck, serverConnectionOnMessage.value)
        Assert.assertEquals(1, serverDisconnectMessage.value)
        Assert.assertTrue(serverConnect.value)
        Assert.assertTrue(serverDisconnect.value)
        Assert.assertTrue(clientConnect.value)
        Assert.assertTrue(clientDisconnect.value)

        Assert.assertFalse(checkFail1.value)
        Assert.assertFalse(checkFail2.value)
    }
}
