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
package dorkboxTest.network.rmi

import dorkbox.netUtil.IPv4
import dorkbox.netUtil.IPv6
import dorkbox.network.Client
import dorkbox.network.Server
import dorkbox.network.connection.Connection
import dorkboxTest.network.BaseTest
import dorkboxTest.network.rmi.cows.MessageWithTestCow
import dorkboxTest.network.rmi.cows.TestCow
import dorkboxTest.network.rmi.cows.TestCowImpl
import org.junit.Assert
import org.junit.Test

class RmiSimpleTest : BaseTest() {

    enum class ConnectType(val ip4: Boolean, val ip6: Boolean, val ipc: Boolean) {
        IPC(false, false, true),
        IPC4(true, false, true),
        IPC6(false, true, true),
        IPC46(true, true, true),
        IPC64(true, true, true),
        IP4(true, false, false),
        IP6(false, true, false),
        IP46(true, true, false),
        IP64(true, true, false)
    }


    @Test
    fun rmiIPv4NetworkGlobal() {
        rmiGlobal(ConnectType.IP4)
    }

    @Test
    fun rmiIPv6NetworkGlobal() {
        rmiGlobal(ConnectType.IP6)
    }

    @Test
    fun rmiBothIPv4ConnectNetworkGlobal() {
        rmiGlobal(ConnectType.IP46)
    }

    @Test
    fun rmiBothIPv6ConnectNetworkGlobal() {
        rmiGlobal(ConnectType.IP64)
    }

    @Test
    fun rmiIpcNetworkGlobal() {
        rmiGlobal(ConnectType.IPC)
    }

    @Test
    fun rmiIpcNetworkGlobalFallback4() {
        rmiGlobal(ConnectType.IPC4)
    }

    @Test
    fun rmiIpcNetworkGlobalFallback6() {
        rmiGlobal(ConnectType.IPC6)
    }

    @Test
    fun rmiIpcNetworkGlobalFallback46() {
        rmiGlobal(ConnectType.IPC46)
    }

    @Test
    fun rmiIpcNetworkGlobalFallback64() {
        rmiGlobal(ConnectType.IPC64)
    }







    @Test
    fun rmiIPv4NetworkConnection() {
        rmi(ConnectType.IP4)
    }

    @Test
    fun rmiIPv6NetworkConnection() {
        rmi(ConnectType.IP6)
    }

    @Test
    fun rmiBothIPv4ConnectNetworkConnection() {
        rmi(ConnectType.IP46)
    }


    @Test
    fun rmiBothIPv6ConnectNetworkConnection() {
        rmi(ConnectType.IP64)
    }

    @Test
    fun rmiIpcNetworkConnection() {
        rmi(ConnectType.IPC)
    }

    @Test
    fun rmiIpcFallback4NetworkConnection() {
        rmi(ConnectType.IPC4)
    }

    @Test
    fun rmiIpcFallback6NetworkConnection() {
        rmi(ConnectType.IPC6)
    }

    @Test
    fun rmiIpcFallback46NetworkConnection() {
        rmi(ConnectType.IPC46)
    }

    @Test
    fun rmiIpcFallback64NetworkConnection() {
        rmi(ConnectType.IPC64)
    }

    // GLOBAL rmi stuff cannot CREATE or DELETE (only save/get)
    private fun rmiGlobal(clientType: ConnectType, serverType: ConnectType = clientType) {
        val server = run {
            val configuration = serverConfig()
            configuration.enableIPv4 = serverType.ip4
            configuration.enableIPv6 = serverType.ip6
            configuration.enableIpc = serverType.ipc

            configuration.serialization.rmi.register(TestCow::class.java, TestCowImpl::class.java)
            configuration.serialization.register(MessageWithTestCow::class.java)
            configuration.serialization.register(UnsupportedOperationException::class.java)

            // for Client -> Server RMI
            configuration.serialization.rmi.register(TestCow::class.java, TestCowImpl::class.java)

            val server = Server<Connection>(configuration)
            addEndPoint(server)

            server.rmiGlobal.save(TestCowImpl(44), 44)

            server.onMessage<MessageWithTestCow> { m ->
                server.logger.error("Received finish signal for test for: Client -> Server")

                val `object` = m.testCow
                val id = `object`.id()

                Assert.assertEquals(44, id)

                server.logger.error("Finished test for: Client -> Server")

                // normally this is in the 'connected', but we do it here, so that it's more linear and easier to debug

                server.logger.error("Running test for: Server -> Client")
                RmiCommonTest.runTests(this@onMessage, rmi.get(4), 4)
                server.logger.error("Done with test for: Server -> Client")
            }

            server
        }

        val client = run {
            val configuration = clientConfig()
            configuration.enableIPv4 = clientType.ip4
            configuration.enableIPv6 = clientType.ip6
            configuration.enableIpc = clientType.ipc
            //            configuration.serialization.registerRmi(TestCow::class.java, TestCowImpl::class.java)

            val client = Client<Connection>(configuration)
            addEndPoint(client)

            client.onConnect {
                rmi.save(TestCowImpl(4), 4)

                client.logger.error("Running test for: Client -> Server")
                RmiCommonTest.runTests(this, rmi.getGlobal(44), 44)
                client.logger.error("Done with test for: Client -> Server")
            }

            client.onMessage<MessageWithTestCow> { m ->
                client.logger.error("Received finish signal for test for: Client -> Server")
                val `object` = m.testCow
                val id = `object`.id()
                Assert.assertEquals(4, id)
                client.logger.error("Finished test for: Client -> Server")
                stopEndPoints()
            }

            client.logger.error("Starting test for: Client -> Server")

            client
        }

        server.bind(2000)
        when (clientType) {
            ConnectType.IPC -> client.connectIpc()
            ConnectType.IPC4 -> client.connect(IPv4.LOCALHOST, 2000)
            ConnectType.IPC6 -> client.connect(IPv6.LOCALHOST, 2000)
            ConnectType.IPC46 -> client.connect(IPv4.LOCALHOST, 2000)
            ConnectType.IPC64 -> client.connect(IPv6.LOCALHOST, 2000)
            ConnectType.IP4 -> client.connect(IPv4.LOCALHOST, 2000)
            ConnectType.IP6 -> client.connect(IPv6.LOCALHOST, 2000)
            ConnectType.IP46 -> client.connect(IPv4.LOCALHOST, 2000)
            ConnectType.IP64 -> client.connect(IPv6.LOCALHOST, 2000)
        }

        waitForThreads()
    }



    fun rmi(clientType: ConnectType, serverType: ConnectType = clientType) {
        val server = run {
            val configuration = serverConfig()
            configuration.enableIPv4 = serverType.ip4
            configuration.enableIPv6 = serverType.ip6
            configuration.enableIpc = serverType.ipc

            configuration.serialization.rmi.register(TestCow::class.java, TestCowImpl::class.java)
            configuration.serialization.register(MessageWithTestCow::class.java)
            configuration.serialization.register(UnsupportedOperationException::class.java)


            val server = Server<Connection>(configuration)
            addEndPoint(server)


            server.onMessage<MessageWithTestCow> { m ->
                server.logger.error("Received finish signal for test for: Client -> Server")
                val `object` = m.testCow
                val id = `object`.id()
                Assert.assertEquals(23, id)
                server.logger.error("Finished test for: Client -> Server")


                server.logger.error("Starting test for: Server -> Client")
                // NOTE: THIS IS BI-DIRECTIONAL!
                rmi.create<TestCow>(123) {
                    server.logger.error("Running test for: Server -> Client")
                    RmiCommonTest.runTests(this@onMessage, this, 123)
                    server.logger.error("Done with test for: Server -> Client")
                }
            }
            server
        }

        val client = run {
            val configuration = clientConfig()
            configuration.enableIPv4 = clientType.ip4
            configuration.enableIPv6 = clientType.ip6
            configuration.enableIpc = clientType.ipc
//            configuration.serialization.registerRmi(TestCow::class.java, TestCowImpl::class.java)

            val client = Client<Connection>(configuration)
            addEndPoint(client)

            client.onConnect {
                rmi.create<TestCow>(23) {
                    client.logger.error("Running test for: Client -> Server")
                    RmiCommonTest.runTests(this@onConnect, this, 23)
                    client.logger.error("Done with test for: Client -> Server")
                }
            }

            client.onMessage<MessageWithTestCow> { m ->
                client.logger.error("Received finish signal for test for: Client -> Server")
                val `object` = m.testCow
                val id = `object`.id()
                Assert.assertEquals(123, id)
                client.logger.error("Finished test for: Client -> Server")
                stopEndPoints()
            }

            client
        }

        server.bind(2000)
        when (clientType) {
            ConnectType.IPC -> client.connectIpc()
            ConnectType.IPC4 -> client.connect(IPv4.LOCALHOST, 2000)
            ConnectType.IPC6 -> client.connect(IPv6.LOCALHOST, 2000)
            ConnectType.IPC46 -> client.connect(IPv4.LOCALHOST, 2000)
            ConnectType.IPC64 -> client.connect(IPv6.LOCALHOST, 2000)
            ConnectType.IP4 -> client.connect(IPv4.LOCALHOST, 2000)
            ConnectType.IP6 -> client.connect(IPv6.LOCALHOST, 2000)
            ConnectType.IP46 -> client.connect(IPv4.LOCALHOST, 2000)
            ConnectType.IP64 -> client.connect(IPv6.LOCALHOST, 2000)
        }

        waitForThreads()
    }
}
