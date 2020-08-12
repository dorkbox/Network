/*
 * Copyright 2016 dorkbox, llc
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

package dorkboxTest.network.rmi

import dorkbox.network.Client
import dorkbox.network.Configuration
import dorkbox.network.Server
import dorkbox.network.connection.Connection
import dorkbox.util.exceptions.SecurityException
import dorkboxTest.network.BaseTest
import dorkboxTest.network.rmi.classes.MessageWithTestCow
import dorkboxTest.network.rmi.classes.TestCow
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Test
import java.io.IOException

class RmiGlobalTest : BaseTest() {
    companion object {
        private const val CLIENT_GLOBAL_OBJECT_ID = 123
        private const val SERVER_GLOBAL_OBJECT_ID = 2453
    }

    @Test
    @Throws(SecurityException::class, IOException::class)
    fun rmiNetwork() {
        rmi()
    }

    @Test
    @Throws(SecurityException::class, IOException::class)
    fun rmiLocal() {
//        rmi(object : Config() {
//            fun apply(configuration: Configuration) {
//                configuration.localChannelName = EndPoint.LOCAL_CHANNEL
//            }
//        })
    }

    fun rmi(config: (Configuration) -> Unit = {}) {
        run {
            val configuration = serverConfig()
            config(configuration)
            RmiTest.register(configuration.serialization)

            // NOTICE: none of the super classes/interfaces are registered!
            configuration.serialization.registerRmi(TestCow::class.java, TestCowImpl::class.java)

            val server = Server<Connection>(configuration)
            addEndPoint(server)

            // register this object as a global object that the client will get
            server.saveGlobalObject(TestCowImpl(SERVER_GLOBAL_OBJECT_ID), SERVER_GLOBAL_OBJECT_ID)

            server.onConnect { connection ->
                val remoteObject = connection.getObject<TestCow>(CLIENT_GLOBAL_OBJECT_ID)

                System.err.println("Running test for: Server (LOCAL) -> Client (REMOTE)")
                RmiTest.runTests(connection, remoteObject, CLIENT_GLOBAL_OBJECT_ID)
                System.err.println("Done with test for: Server (LOCAL) -> Client (REMOTE)")
            }

            server.onMessage<MessageWithTestCow> { connection, message ->
                System.err.println("Received finish signal for test for: Client (LOCAL) -> Server (REMOTE)")
                val `object`: TestCow = message.testCow
                val id: Int = `object`.id()

                Assert.assertEquals(SERVER_GLOBAL_OBJECT_ID.toLong(), id.toLong())
                System.err.println("Finished test for: Client (LOCAL) -> Server (REMOTE)")

                stopEndPoints(2000)
            }

            server.bind(false)
        }

        run {
            val configuration = clientConfig()
            config(configuration)
            RmiTest.register(configuration.serialization)

            // NOTICE: none of the super classes/interfaces are registered!
            configuration.serialization.registerRmi(TestCow::class.java, TestCowImpl::class.java)

            val client = Client<Connection>(configuration)
            addEndPoint(client)

            // register this object as a global object that the server will get
            client.saveObject(TestCowImpl(CLIENT_GLOBAL_OBJECT_ID), CLIENT_GLOBAL_OBJECT_ID)

            client.onMessage<MessageWithTestCow> { connection, message ->
                System.err.println("Received finish signal for test for: Server (LOCAL) -> Client (REMOTE)")

                // this TestCow object should be the implementation, not the proxy.
                val `object`: TestCow = message.testCow
                val id: Int = `object`.id()
                Assert.assertEquals(CLIENT_GLOBAL_OBJECT_ID.toLong(), id.toLong())
                System.err.println("Finished test for: Server (LOCAL) -> Client (REMOTE)")

                // normally this is in the 'connected', but we do it here, so that it's more linear and easier to debug
                val remoteCow = connection.getGlobalObject<TestCow>(SERVER_GLOBAL_OBJECT_ID)

                System.err.println("Running test for: Client (LOCAL) -> Server (REMOTE)")
                RmiTest.runTests(connection, remoteCow, SERVER_GLOBAL_OBJECT_ID)
                System.err.println("Done with test for: Client (LOCAL) -> Server (REMOTE)")
            }

            runBlocking {
                client.connect(LOOPBACK)
            }
        }

        waitForThreads()
    }

    private open class ConnectionAware {
        var connection: Connection? = null
    }

    private class TestCowImpl(private val id: Int) : ConnectionAware(),
                                                     TestCow {
        var value = System.currentTimeMillis()
        var moos = 0

        override fun throwException() {
            throw UnsupportedOperationException("Why would I do that?")
        }

        override fun moo() {
            moos++
            println("Moo!")
        }

        override fun moo(value: String) {
            moos += 2
            println("Moo: $value")
        }

        override fun moo(value: String, delay: Long) {
            moos += 4
            println("Moo: $value ($delay)")
            try {
                Thread.sleep(delay)
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        }

        override fun id(): Int {
            return id
        }

        override fun slow(): Float {
            println("Slowdown!!")
            try {
                Thread.sleep(2000)
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
            return 123.0f
        }
    }
}
