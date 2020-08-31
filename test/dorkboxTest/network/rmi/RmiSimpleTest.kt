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
import dorkboxTest.network.BaseTest
import dorkboxTest.network.rmi.cows.MessageWithTestCow
import dorkboxTest.network.rmi.cows.TestCow
import dorkboxTest.network.rmi.cows.TestCowImpl
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Test

class RmiSimpleTest : BaseTest() {

    @Test
    fun rmiNetworkGlobal() {
        rmiGlobal()
    }

    @Test
    fun rmiNetworkConnection() {
        rmi()
    }
//
//    @Test
//    @Throws(SecurityException::class, IOException::class, InterruptedException::class)
//    fun rmiIPC() {
//        rmi { configuration ->
//            if (configuration is ServerConfiguration) {
//                configuration.listenIpAddress = LOOPBACK
//            }
//        }
//    }

    fun rmi(config: (Configuration) -> Unit = {}) {
        run {
            val configuration = serverConfig()
            config(configuration)
            RmiCommonTest.register(configuration.serialization)

            // for Client -> Server RMI
            configuration.serialization.registerRmi(TestCow::class.java, TestCowImpl::class.java)

            val server = Server<Connection>(configuration)
            addEndPoint(server)

            runBlocking {
                server.bind(false)
            }

            server.onMessage<MessageWithTestCow> { connection, m ->
                System.err.println("Received finish signal for test for: Client -> Server")
                val `object` = m.testCow
                val id = `object`.id()
                Assert.assertEquals(23, id.toLong())
                System.err.println("Finished test for: Client -> Server")


                System.err.println("Starting test for: Server -> Client")
                connection.createObject<TestCow>(123) { rmiId, remoteObject ->
                    System.err.println("Running test for: Server -> Client")
                    RmiCommonTest.runTests(connection, remoteObject, 123)
                    System.err.println("Done with test for: Server -> Client")
                }
            }
        }

        run {
            val configuration = clientConfig()
            config(configuration)
            RmiCommonTest.register(configuration.serialization)

            // for Server -> Client RMI
            configuration.serialization.registerRmi(TestCow::class.java, TestCowImpl::class.java)

            val client = Client<Connection>(configuration)
            addEndPoint(client)

            client.onConnect { connection ->
                connection.createObject<TestCow>(23) { rmiId, remoteObject ->
                    System.err.println("Running test for: Client -> Server")
                    RmiCommonTest.runTests(connection, remoteObject, 23)
                    System.err.println("Done with test for: Client -> Server")
                }
            }

            client.onMessage<MessageWithTestCow> { _, m ->
                System.err.println("Received finish signal for test for: Client -> Server")
                val `object` = m.testCow
                val id = `object`.id()
                Assert.assertEquals(123, id.toLong())
                System.err.println("Finished test for: Client -> Server")
                stopEndPoints(2000)
            }

            runBlocking {
                client.connect(LOOPBACK)
            }
        }

        waitForThreads(99999999)
    }

    fun rmiGlobal(config: (Configuration) -> Unit = {}) {
        run {
            val configuration = serverConfig()
            config(configuration)
            RmiCommonTest.register(configuration.serialization)

            // for Client -> Server RMI
            configuration.serialization.registerRmi(TestCow::class.java, TestCowImpl::class.java)

            val server = Server<Connection>(configuration)
            addEndPoint(server)

            runBlocking {
                server.bind(false)
            }

            server.onMessage<MessageWithTestCow> { connection, m ->
                System.err.println("Received finish signal for test for: Client -> Server")

                val `object` = m.testCow
                val id = `object`.id()

                Assert.assertEquals(44, id.toLong())

                System.err.println("Finished test for: Client -> Server")

                // normally this is in the 'connected', but we do it here, so that it's more linear and easier to debug
                connection.createObject<TestCow>(4) { rmiId, remoteObject ->
                    System.err.println("Running test for: Server -> Client")
                    RmiCommonTest.runTests(connection, remoteObject, 4)
                    System.err.println("Done with test for: Server -> Client")
                }
            }
        }

        run {
            val configuration = clientConfig()
            config(configuration)
            RmiCommonTest.register(configuration.serialization)

            // for Server -> Client RMI
            configuration.serialization.registerRmi(TestCow::class.java, TestCowImpl::class.java)
            val client = Client<Connection>(configuration)
            addEndPoint(client)

            client.onMessage<MessageWithTestCow> { _, m ->
                System.err.println("Received finish signal for test for: Client -> Server")
                val `object` = m.testCow
                val id = `object`.id()
                Assert.assertEquals(4, id.toLong())
                System.err.println("Finished test for: Client -> Server")
                stopEndPoints(2000)
            }

            runBlocking {
                client.connect(LOOPBACK)

                System.err.println("Starting test for: Client -> Server")

                // this creates a GLOBAL object on the server (instead of a connection specific object)
                client.createObject<TestCow>(44) { rmiId, remoteObject ->
                    System.err.println("Running test for: Client -> Server")
                    RmiCommonTest.runTests(client.getConnection(), remoteObject, 44)
                    System.err.println("Done with test for: Client -> Server")
                }
            }
        }

        waitForThreads()
    }
}
