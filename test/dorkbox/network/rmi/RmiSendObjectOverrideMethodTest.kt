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

class RmiSendObjectOverrideMethodTest : BaseTest() {

    companion object {
        private val idCounter = AtomicInteger()
    }

    @Test
    @Throws(SecurityException::class, IOException::class)
    fun rmiNetwork() {
        rmi()
    }

    @Test
    @Throws(SecurityException::class, IOException::class)
    fun rmiIPC() {
        TODO("DO IPC STUFF!")
        // rmi(new Config() {
        //     @Override
        //     public
        //     void apply(final Configuration configuration) {
        //         configuration.localChannelName = EndPoint.LOCAL_CHANNEL;
        //     }
        // });
    }

    /**
     * In this test the server has two objects in an object space.
     *
     * The client uses the first remote object to get the second remote object.
     *
     *
     * The MAJOR difference in this version, is that we use an interface to override the methods, so that we can have the RMI system pass
     * in the connection object.
     *
     * Specifically, from CachedMethod.java
     *
     * In situations where we want to pass in the Connection (to an RMI method), we have to be able to override method A, with method B.
     * This is to support calling RMI methods from an interface (that does pass the connection reference) to
     * an implType, that DOES pass the connection reference. The remote side (that initiates the RMI calls), MUST use
     * the interface, and the implType may override the method, so that we add the connection as the first in
     * the list of parameters.
     *
     * for example:
     * Interface: foo(String x)
     * Impl: foo(Connection c, String x)
     *
     * The implType (if it exists, with the same name, and with the same signature + connection parameter) will be called from the interface
     * instead of the method that would NORMALLY be called.
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

            server.onMessage<OtherObject> { connection, message ->
                // The test is complete when the client sends the OtherObject instance.

                // this 'object' is the REAL object, not a proxy, because this object is created within this connection.
                if (message.value() == 12.34f) {
                    stopEndPoints()
                } else {
                    Assert.fail("Incorrect object value")
                }
            }


            server.bind(false)
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
                // if this is called in the dispatch thread, it will block network comms while waiting for a response and it won't work...
//                connection.create(TestObject::class.java, object : RemoteObjectCallback<TestObject> {
//                    override suspend fun created(remoteObject: TestObject) {
//                        // MUST run on a separate thread because remote object method invocations are blocking
//                        object : Thread() {
//                            override fun run() {
//                                remoteObject.setOther(43.21f)
//
//                                // Normal remote method call.
//                                Assert.assertEquals(43.21f, remoteObject.other(), .0001f)
//
//                                // Make a remote method call that returns another remote proxy object.
//                                // the "test" object exists in the REMOTE side, as does the "OtherObject" that is created.
//                                //  here we have a proxy to both of them.
//                                val otherObject = remoteObject.getOtherObject()
//
//                                // Normal remote method call on the second object.
//                                otherObject.setValue(12.34f)
//                                val value = otherObject.value()
//                                Assert.assertEquals(12.34f, value, .0001f)
//
//                                // When a proxy object is sent, the other side receives its ACTUAL object (not a proxy of it), because
//                                // that is where that object actually exists.
//                                runBlocking {
//                                    connection.send(otherObject)
//                                }
//                            }
//                        }.start()
//                    }
//                })
            }

            runBlocking {
                client.connect(LOOPBACK, 5000)
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
            throw RuntimeException("Whoops!")
        }

        fun setOther(connection: Connection, aFloat: Float) {
            this.aFloat = aFloat
        }

        override fun other(): Float {
            throw RuntimeException("Whoops!")
        }

        fun other(connection: Connection): Float {
            return aFloat
        }

        override fun getOtherObject(): OtherObject {
            throw RuntimeException("Whoops!")
        }

        fun getOtherObject(connection: Connection): OtherObject {
            return otherObject
        }

        override fun hashCode(): Int {
            return ID
        }
    }

    class OtherObjectImpl : OtherObject {
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
}
