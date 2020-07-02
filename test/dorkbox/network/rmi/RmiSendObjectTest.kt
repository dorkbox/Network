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
package dorkbox.network.rmi

import dorkbox.network.BaseTest
import dorkbox.network.Client
import dorkbox.network.Configuration
import dorkbox.network.Server
import dorkbox.network.connection.Connection
import dorkbox.util.exceptions.SecurityException
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class RmiSendObjectTest : BaseTest() {
    @Test
    @Throws(SecurityException::class, IOException::class)
    fun rmiNetwork() {
        rmi()
    }

    @Test
    @Throws(SecurityException::class, IOException::class)
    fun rmiIPC() {
        // rmi(new Config() {
        //     @Override
        //     public
        //     void apply(final Configuration configuration) {
        //         configuration.localChannelName = EndPoint.LOCAL_CHANNEL;
        //     }
        // });
    }

    /**
     * In this test the server has two objects in an object space. The client
     * uses the first remote object to get the second remote object.
     */
    @Throws(SecurityException::class, IOException::class)
    fun rmi(config: (Configuration) -> Unit = {}) {
        run {
            val configuration = serverConfig()
            config(configuration)

            configuration.serialization.registerRmi(TestObject::class.java, TestObjectImpl::class.java)
            configuration.serialization.registerRmi(OtherObject::class.java, OtherObjectImpl::class.java)
            configuration.serialization.register(OtherObjectImpl::class.java) // registered because this class is sent over the wire

            val server = Server<Connection>(configuration)
            addEndPoint(server)
            server.bind(false)

            server.onMessage<OtherObjectImpl> { connection, message ->
                // The test is complete when the client sends the OtherObject instance.
                if (message.value() == 12.34f) {
                    stopEndPoints()
                } else {
                    Assert.fail("Incorrect object value")
                }
            }
        }

        run {
            val configuration = clientConfig()
            config(configuration)

            configuration.serialization.registerRmi(TestObject::class.java, TestObjectImpl::class.java)
            configuration.serialization.registerRmi(OtherObject::class.java, OtherObjectImpl::class.java)
            configuration.serialization.register(OtherObjectImpl::class.java) // registered because this class is sent over the wire

            val client = Client<Connection>(configuration)
            addEndPoint(client)
            client.onConnect { connection ->
                connection.createRemoteObject(TestObject::class.java, object : RemoteObjectCallback<TestObject> {
                    override fun created(remoteObject: TestObject) {
                        // MUST run on a separate thread because remote object method invocations are blocking
                        object : Thread() {
                            override fun run() {
                                remoteObject.setOther(43.21f)

                                // Normal remote method call.
                                Assert.assertEquals(43.21f, remoteObject.other(), 0.0001f)

                                // Make a remote method call that returns another remote proxy object.
                                val otherObject = remoteObject.getOtherObject()

                                // Normal remote method call on the second object.
                                otherObject.setValue(12.34f)
                                val value = otherObject.value()
                                Assert.assertEquals(12.34f, value, 0.0001f)

                                // When a remote proxy object is sent, the other side receives its actual remote object.
                                runBlocking {
                                    connection.send(otherObject)
                                }
                            }
                        }.start()
                    }
                })
            }

            runBlocking {
                client.connect(LOOPBACK)
            }
        }

        waitForThreads()
    }

    private interface TestObject {
        fun setOther(aFloat: Float)
        fun other(): Float
        fun getOtherObject(): OtherObject
    }

    private interface OtherObject {
        fun setValue(aFloat: Float)
        fun value(): Float
    }

    private class TestObjectImpl : TestObject {
        @Transient
        private val ID = idCounter.getAndIncrement()

        @Rmi
        private val otherObject: OtherObject = OtherObjectImpl()

        private var aFloat = 0f

        override fun setOther(aFloat: Float) {
            this.aFloat = aFloat
        }

        override fun other(): Float {
            return aFloat
        }

        override fun getOtherObject(): OtherObject {
            return otherObject
        }

        override fun hashCode(): Int {
            return ID
        }
    }

    private class OtherObjectImpl : OtherObject {
        @Transient
        private val ID = idCounter.getAndIncrement()
        private var aFloat = 0f

        override fun setValue(aFloat: Float) {
            this.aFloat = aFloat
        }

        override fun value(): Float {
            return aFloat
        }

        override fun hashCode(): Int {
            return ID
        }
    }

    companion object {
        private val idCounter = AtomicInteger()
    }
}
