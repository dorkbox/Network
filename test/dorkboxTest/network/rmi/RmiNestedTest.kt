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
 */
package dorkboxTest.network.rmi

import dorkbox.network.Client
import dorkbox.network.Server
import dorkbox.network.connection.Connection
import dorkboxTest.network.BaseTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger



@Suppress("unused", "RedundantSuspendModifier")
class RmiNestedTest : BaseTest() {

    companion object {
        private val idCounter = AtomicInteger()
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
    @Test
    fun biDirectionalDoubleRmi() {
        run {
            val configuration = serverConfig()

            configuration.serialization.registerRmi(TestObject::class.java, TestObjectAnnotImpl::class.java)
            configuration.serialization.registerRmi(OtherObject::class.java, OtherObjectImpl::class.java)

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

            server.bind()
        }


        run {
            val configuration = clientConfig()

            val client = Client<Connection>(configuration)
            addEndPoint(client)

            client.onConnect { connection ->
                connection.logger.error("Connected")
                connection.createObject<TestObject> {
                    connection.logger.error("Starting test")
                    setValue(43.21f)

                    // Normal remote method call.
                    Assert.assertEquals(43.21f, other(), .0001f)

                    // Make a remote method call that returns another remote proxy object.
                    // the "test" object exists in the REMOTE side, as does the "OtherObject" that is created.
                    //  here we have a proxy to both of them.
                    val otherObject: OtherObject = getOtherObject()

                    // Normal remote method call on the second object.
                    otherObject.setValue(12.34f)
                    val value = otherObject.value()
                    Assert.assertEquals(12.34f, value, .0001f)


                    // make sure the "local" object and the "remote" object have the same values
                    Assert.assertEquals(12.34f, getOtherValue(), .0001f)


                    // When a proxy object is sent, the other side receives its ACTUAL object (not a proxy of it), because
                    // that is where that object actually exists.
                    connection.send(otherObject)
                }
            }

            runBlocking {
                client.connect(LOOPBACK, 5000)
            }
        }
        waitForThreads()
    }

    @Test
    fun doubleRmi() {
        run {
            val configuration = serverConfig()
            configuration.serialization.registerRmi(TestObject::class.java, TestObjectAnnotImpl::class.java)
            configuration.serialization.registerRmi(OtherObject::class.java, OtherObjectImpl::class.java)

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

            server.bind()
        }


        run {
            val configuration = clientConfig()

            val client = Client<Connection>(configuration)
            addEndPoint(client)

            client.onConnect { connection ->
                connection.logger.error("Connected")
                connection.createObject<TestObject> {
                    connection.logger.error("Starting test")
                    setValue(43.21f)

                    // Normal remote method call.
                    Assert.assertEquals(43.21f, other(), .0001f)

                    // Make a remote method call that returns another remote proxy object.
                    // the "test" object exists in the REMOTE side, as does the "OtherObject" that is created.
                    //  here we have a proxy to both of them.
                    val otherObject: OtherObject = getOtherObject()

                    // Normal remote method call on the second object.
                    otherObject.setValue(12.34f)
                    val value = otherObject.value()
                    Assert.assertEquals(12.34f, value, .0001f)


                    // make sure the "local" object and the "remote" object have the same values
                    Assert.assertEquals(12.34f, getOtherValue(), .0001f)


                    // When a proxy object is sent, the other side receives its ACTUAL object (not a proxy of it), because
                    // that is where that object actually exists.
                    connection.send(otherObject)
                }
            }

            runBlocking {
                client.connect(LOOPBACK, 5000)
            }
        }
        waitForThreads()
    }

    @Test
    fun singleRmi() {
        run {
            val configuration = serverConfig()
            configuration.serialization.registerRmi(TestObject::class.java, TestObjectImpl::class.java)
            configuration.serialization.register(OtherObjectImpl::class.java)

            val server = Server<Connection>(configuration)
            addEndPoint(server)

            server.onMessage<OtherObject> { connection, message ->
                // The test is complete when the client sends the OtherObject instance.
                // this 'object' is the REAL object
                if (message.value() == 43.21f) {
                    stopEndPoints()
                } else {
                    Assert.fail("Incorrect object value")
                }
            }

            server.bind()
        }


        run {
            val configuration = clientConfig()

            val client = Client<Connection>(configuration)
            addEndPoint(client)

            client.onConnect { connection ->
                connection.logger.error("Connected")
                connection.createObject<TestObject> {
                    connection.logger.error("Starting test")
                    setOtherValue(43.21f)

                    // Normal remote method call.
                    Assert.assertEquals(43.21f, getOtherValue(), .0001f)

                    // real object
                    val otherObject: OtherObject = getOtherObject()

                    // Normal remote method call on the second object.
                    val value = otherObject.value()
                    Assert.assertEquals(43.21f, value, .0001f)


                    // When a proxy object is sent, the other side receives its ACTUAL object (not a proxy of it), because
                    // that is where that object actually exists.
                    connection.send(otherObject)
                }
            }

            runBlocking {
                client.connect(LOOPBACK, 5000)
            }
        }
        waitForThreads()
    }

    @Test
    fun singleReverseRmi() {
        run {
            val configuration = serverConfig()
            configuration.serialization.registerRmi(TestObject::class.java, null)
            configuration.serialization.register(OtherObjectImpl::class.java)

            val server = Server<Connection>(configuration)
            addEndPoint(server)

            server.onConnect { connection ->
                connection.logger.error("Connected")
                connection.createObject<TestObject> {
                    connection.logger.error("Starting test")
                    setOtherValue(43.21f)

                    // Normal remote method call.
                    Assert.assertEquals(43.21f, getOtherValue(), .0001f)

                    // real object
                    val otherObject: OtherObject = getOtherObject()

                    // Normal remote method call on the second object.
                    val value = otherObject.value()
                    Assert.assertEquals(43.21f, value, .0001f)


                    // When a proxy object is sent, the other side receives its ACTUAL object (not a proxy of it), because
                    // that is where that object actually exists.
                    connection.send(otherObject)
                }
            }

            server.bind()
        }


        run {
            val configuration = clientConfig()
            configuration.serialization.registerRmi(TestObject::class.java, TestObjectImpl::class.java)

            val client = Client<Connection>(configuration)
            addEndPoint(client)

            client.onMessage<OtherObject> { connection, message ->
                // The test is complete when the client sends the OtherObject instance.
                // this 'object' is the REAL object
                if (message.value() == 43.21f) {
                    stopEndPoints()
                } else {
                    Assert.fail("Incorrect object value")
                }
            }

            runBlocking {
                client.connect(LOOPBACK, 5000)
            }
        }
        waitForThreads()
    }





    private interface TestObject {
        suspend fun setValue(aFloat: Float)
        suspend fun setOtherValue(aFloat: Float)
        suspend fun getOtherValue(): Float
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

        private val otherObject: OtherObject = OtherObjectImpl()

        private var aFloat = 0f
        override suspend fun setValue(aFloat: Float) {
            throw RuntimeException("Whoops!")
        }

        suspend fun setValue(connection: Connection, aFloat: Float) {
            connection.logger.error("receiving")
            this.aFloat = aFloat
        }

        override suspend fun setOtherValue(aFloat: Float) {
            otherObject.setValue(aFloat)
        }

        override suspend fun getOtherValue(): Float {
            return otherObject.value()
        }

        override fun other(): Float {
            throw RuntimeException("Whoops!")
        }

        fun other(connection: Connection): Float {
            return aFloat
        }

        override fun getOtherObject(): OtherObject {
            return otherObject
        }

        override fun hashCode(): Int {
            return ID
        }
    }

    private class TestObjectAnnotImpl : TestObject {
        @Transient
        private val ID = idCounter.getAndIncrement()

        private val otherObject: OtherObject = OtherObjectImpl()

        private var aFloat = 0f
        override suspend fun setValue(aFloat: Float) {
            throw RuntimeException("Whoops!")
        }

        suspend fun setValue(connection: Connection, aFloat: Float) {
            connection.logger.error("receiving")
            this.aFloat = aFloat
        }

        override suspend fun setOtherValue(aFloat: Float) {
            otherObject.setValue(aFloat)
        }

        override suspend fun getOtherValue(): Float {
            return otherObject.value()
        }

        override fun other(): Float {
            throw RuntimeException("Whoops!")
        }

        fun other(connection: Connection): Float {
            return aFloat
        }

        override fun getOtherObject(): OtherObject {
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
