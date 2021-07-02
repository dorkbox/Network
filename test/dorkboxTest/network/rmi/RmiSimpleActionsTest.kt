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

import dorkbox.netUtil.IPv4
import dorkbox.netUtil.IPv6
import dorkbox.network.Client
import dorkbox.network.Configuration
import dorkbox.network.Server
import dorkbox.network.connection.Connection
import dorkbox.network.rmi.RemoteObject
import dorkboxTest.network.BaseTest
import dorkboxTest.network.rmi.cows.MessageWithTestCow
import dorkboxTest.network.rmi.cows.TestCow
import dorkboxTest.network.rmi.cows.TestCowImpl
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Test

class RmiSimpleActionsTest : BaseTest() {

    @Test
    fun rmiIPv4NetworkGlobalDelete() {
        rmiConnectionDelete(isIpv4 = true, isIpv6 = false)
    }





    @Test
    fun testGlobalDeleteServer() {
        val OBJ_ID = 3423

        val configuration = serverConfig()
        configuration.serialization.rmi.register(TestCow::class.java, TestCowImpl::class.java)
        configuration.serialization.register(MessageWithTestCow::class.java)
        configuration.serialization.register(UnsupportedOperationException::class.java)

        // for Client -> Server RMI
        configuration.serialization.rmi.register(TestCow::class.java, TestCowImpl::class.java)

        val server = Server<Connection>(configuration)
        addEndPoint(server)

        val testCowImpl = TestCowImpl(OBJ_ID)
        server.rmiGlobal.save(testCowImpl, OBJ_ID)
//        Assert.assertNotNull(server.rmi.get(OBJ_ID))

        Assert.assertTrue(server.rmiGlobal.delete(testCowImpl))
        Assert.assertFalse(server.rmiGlobal.delete(testCowImpl))
        Assert.assertFalse(server.rmiGlobal.delete(OBJ_ID))
//        Assert.assertNull(server.rmi.get(OBJ_ID))

        val newId = server.rmiGlobal.save(testCowImpl)
//        Assert.assertNotNull(server.rmi.get(newId))

        Assert.assertTrue(server.rmiGlobal.delete(newId))
        Assert.assertFalse(server.rmiGlobal.delete(newId))
        Assert.assertFalse(server.rmiGlobal.delete(testCowImpl))
//        Assert.assertNull(server.rmi.get(newId))
    }

    @Test
    fun testGlobalDelete() {
        val SERVER_ID = 1123
        val CLIENT_ID = 3423

        val server = run {
            val configuration = serverConfig()
            configuration.serialization.rmi.register(TestCow::class.java, TestCowImpl::class.java)

            val server = Server<Connection>(configuration)
            addEndPoint(server)
            server.bind()

            server
        }


        val client = run {
            val configuration = clientConfig()
            configuration.serialization.rmi.register(TestCow::class.java, TestCowImpl::class.java)

            val client = Client<Connection>(configuration)
            addEndPoint(client)
            client.connect(LOOPBACK)

            client
        }

        val serverCow = TestCowImpl(SERVER_ID)
        server.rmiGlobal.save(serverCow, SERVER_ID)

//        val clientCow = TestCowImpl(CLIENT_ID)
//        client.rmi.save(serverCow, CLIENT_ID)

        val get1 = client.connection.rmi.get<TestCow>(CLIENT_ID)
        get1 as RemoteObject
        get1.enableEquals(true)

        Assert.assertNotNull(get1)
        Assert.assertTrue(get1 == serverCow)

//        Assert.assertTrue(client.rmi.delete(clientCow))
//        Assert.assertFalse(client.rmi.delete(clientCow))
//        Assert.assertFalse(client.rmi.delete(CLIENT_ID))
//        Assert.assertNull(client.rmi.get(CLIENT_ID))
//
//        val newId = client.rmi.save(clientCow)
//        Assert.assertNotNull(client.rmi.get(CLIENT_ID))
//
//        Assert.assertTrue(client.rmi.delete(CLIENT_ID))
//        Assert.assertFalse(client.rmi.delete(CLIENT_ID))
//        Assert.assertFalse(client.rmi.delete(clientCow))
//        Assert.assertNull(client.rmi.get(CLIENT_ID))

        runBlocking {
            stopEndPoints()
            waitForThreads()
        }
    }

    @Test
    fun testGlobalDelete3() {
        val OBJ_ID = 3423

        val configuration = serverConfig()
        configuration.serialization.rmi.register(TestCow::class.java, TestCowImpl::class.java)
        configuration.serialization.register(MessageWithTestCow::class.java)
        configuration.serialization.register(UnsupportedOperationException::class.java)

        // for Client -> Server RMI
        configuration.serialization.rmi.register(TestCow::class.java, TestCowImpl::class.java)

        val server = Server<Connection>(configuration)
        addEndPoint(server)

        val testCowImpl = TestCowImpl(OBJ_ID)
        server.rmiGlobal.save(testCowImpl, OBJ_ID)
        Assert.assertTrue(server.rmiGlobal.delete(OBJ_ID))
        Assert.assertFalse(server.rmiGlobal.delete(testCowImpl))
    }

    @Test
    fun testGlobalDelete4() {
        val OBJ_ID = 3423

        val configuration = serverConfig()
        configuration.serialization.rmi.register(TestCow::class.java, TestCowImpl::class.java)
        configuration.serialization.register(MessageWithTestCow::class.java)
        configuration.serialization.register(UnsupportedOperationException::class.java)

        // for Client -> Server RMI
        configuration.serialization.rmi.register(TestCow::class.java, TestCowImpl::class.java)

        val server = Server<Connection>(configuration)
        addEndPoint(server)

        val testCowImpl = TestCowImpl(OBJ_ID)
        server.rmiGlobal.save(testCowImpl, OBJ_ID)
        Assert.assertTrue(server.rmiGlobal.delete(OBJ_ID))
        Assert.assertFalse(server.rmiGlobal.delete(testCowImpl))
    }



    fun rmiConnectionDelete(isIpv4: Boolean = false, isIpv6: Boolean = false, runIpv4Connect: Boolean = true, config: Configuration.() -> Unit = {}) {
        val OBJ_ID = 3423

        run {
            val configuration = serverConfig()
            configuration.enableIPv4 = isIpv4
            configuration.enableIPv6 = isIpv6
            config(configuration)

            configuration.serialization.rmi.register(TestCow::class.java, TestCowImpl::class.java)
            configuration.serialization.register(MessageWithTestCow::class.java)
            configuration.serialization.register(UnsupportedOperationException::class.java)

            // for Client -> Server RMI
            configuration.serialization.rmi.register(TestCow::class.java, TestCowImpl::class.java)

            val server = Server<Connection>(configuration)
            addEndPoint(server)
            server.bind()

            server.rmiGlobal.save(TestCowImpl(44), 44)

            server.onMessage<MessageWithTestCow> { m ->
                server.logger.error("Received finish signal for test for: Client -> Server")

                val `object` = m.testCow
                val id = `object`.id()

                Assert.assertEquals(44, id.toLong())

                server.logger.error("Finished test for: Client -> Server")

                // normally this is in the 'connected', but we do it here, so that it's more linear and easier to debug
                server.logger.error("Running test for: Server -> Client")
                RmiCommonTest.runTests(this@onMessage, rmi.getGlobal(4), 4)
                server.logger.error("Done with test for: Server -> Client")
            }
        }

        run {
            val configuration = clientConfig()
            config(configuration)
//            configuration.serialization.registerRmi(TestCow::class.java, TestCowImpl::class.java)

            val client = Client<Connection>(configuration)
            addEndPoint(client)

            client.onConnect {
                rmi.save(TestCowImpl(4), 4)
            }


            client.onMessage<MessageWithTestCow> { m ->
                client.logger.error("Received finish signal for test for: Client -> Server")
                val `object` = m.testCow
                val id = `object`.id()
                Assert.assertEquals(4, id.toLong())
                client.logger.error("Finished test for: Client -> Server")
                stopEndPoints(2000)
            }

            when {
                isIpv4 && isIpv6 && runIpv4Connect -> client.connect(IPv4.LOCALHOST)
                isIpv4 && isIpv6 && !runIpv4Connect -> client.connect(IPv6.LOCALHOST)
                isIpv4 -> client.connect(IPv4.LOCALHOST)
                isIpv6 -> client.connect(IPv6.LOCALHOST)
                else -> client.connect()
            }

            client.logger.error("Starting test for: Client -> Server")

            // this creates a GLOBAL object on the server (instead of a connection specific object)
            runBlocking {
//                client.rmi.get()


//                client.createObject<TestCow>(44) {
//                    client.deleteObject(this)

//                    client.logger.error("Running test for: Client -> Server")
//                    RmiCommonTest.runTests(client.connection, this, 44)
//                    client.logger.error("Done with test for: Client -> Server")
//                }
            }
        }

        waitForThreads()
    }
}
