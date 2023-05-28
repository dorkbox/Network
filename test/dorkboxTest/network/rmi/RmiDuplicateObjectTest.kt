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
import dorkbox.network.Configuration
import dorkbox.network.Server
import dorkbox.network.connection.Connection
import dorkboxTest.network.BaseTest
import dorkboxTest.network.rmi.cows.MessageWithTestCow
import dorkboxTest.network.rmi.cows.TestCow
import dorkboxTest.network.rmi.cows.TestCowImpl
import org.junit.Assert
import org.junit.Test

class RmiDuplicateObjectTest : BaseTest() {
    @Test
    fun rmiIPv4NetworkConnection() {
        rmi(isIpv4 = true, isIpv6 = false)
    }

    @Test
    fun rmiIPv6NetworkConnection() {
        rmi(isIpv4 = false, isIpv6 = true)
    }

    @Test
    fun rmiBothIPv4ConnectNetworkConnection() {
        rmi(isIpv4 = true, isIpv6 = true)
    }


    @Test
    fun rmiBothIPv6ConnectNetworkConnection() {
        rmi(isIpv4 = true, isIpv6 = true, runIpv4Connect = true)
    }

    @Test
    fun rmiIpcNetworkConnection() {
        rmi {
            enableIpc = true
        }
    }

    private fun doConnect(isIpv4: Boolean, isIpv6: Boolean, runIpv4Connect: Boolean, client: Client<Connection>) {
        when {
            isIpv4 && isIpv6 && runIpv4Connect -> client.connect(IPv4.LOCALHOST)
            isIpv4 && isIpv6 && !runIpv4Connect -> client.connect(IPv6.LOCALHOST)
            isIpv4 -> client.connect(IPv4.LOCALHOST)
            isIpv6 -> client.connect(IPv6.LOCALHOST)
            else -> client.connect()
        }
    }

    private val objs = mutableSetOf<Int>()

    fun rmi(isIpv4: Boolean = false, isIpv6: Boolean = false, runIpv4Connect: Boolean = true, config: Configuration.() -> Unit = {}) {
        run {
            val configuration = serverConfig()
            configuration.enableIPv4 = isIpv4
            configuration.enableIPv6 = isIpv6
            config(configuration)

            configuration.serialization.rmi.register(TestCow::class.java, TestCowImpl::class.java)
            configuration.serialization.register(MessageWithTestCow::class.java)
            configuration.serialization.register(UnsupportedOperationException::class.java)


            val server = Server<Connection>(configuration)
            addEndPoint(server)
            server.bind()

            server.onConnect {
                server.forEachConnection {
                    val testCow = it.rmi.get<TestCow>(4)
                    testCow.moo()

                    synchronized(objs) {
                        objs.add(testCow.id())
                    }
                }
            }
        }

        run {
            val configuration = clientConfig()
            config(configuration)

            val client = Client<Connection>(configuration)
            addEndPoint(client)

            client.onInit {
                rmi.save(TestCowImpl(4), 4)
            }

            doConnect(isIpv4, isIpv6, runIpv4Connect, client)
        }
        run {
            val configuration = clientConfig()
            config(configuration)

            val client = Client<Connection>(configuration)
            addEndPoint(client)


            client.onInit {
                rmi.save(TestCowImpl(5), 4)
            }

            doConnect(isIpv4, isIpv6, runIpv4Connect, client)
        }

        waitForThreads(5)


        val actual = synchronized(objs) {
            objs.joinToString()
        }

        Assert.assertEquals("4, 5", actual)
    }
}
