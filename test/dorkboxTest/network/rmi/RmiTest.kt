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
import dorkbox.network.ServerConfiguration
import dorkbox.network.connection.Connection
import dorkbox.network.rmi.RemoteObject
import dorkbox.network.serialization.NetworkSerializationManager
import dorkbox.util.exceptions.SecurityException
import dorkboxTest.network.BaseTest
import dorkboxTest.network.rmi.classes.MessageWithTestCow
import dorkboxTest.network.rmi.classes.TestCow
import dorkboxTest.network.rmi.classes.TestCowImpl
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Test
import java.io.IOException

class RmiTest : BaseTest() {

    companion object {
        suspend fun runTests(connection: Connection, test: TestCow, remoteObjectID: Int) {
            val remoteObject = test as RemoteObject

            // Default behavior. RMI is transparent, method calls behave like normal
            // (return values and exceptions are returned, call is synchronous)
            System.err.println("hashCode: " + test.hashCode())
            System.err.println("toString: $test")

            // see what the "remote" toString() method is
            val s = remoteObject.toString()
            remoteObject.enableToString(true)
            Assert.assertFalse(s == remoteObject.toString())
            test.moo()
            test.moo("Cow")
            Assert.assertEquals(remoteObjectID.toLong(), test.id().toLong())

            // Test that RMI correctly waits for the remotely invoked method to exit
            remoteObject.responseTimeout = 5000
            test.moo("You should see this two seconds before...", 2000)
            println("...This")
            remoteObject.responseTimeout = 3000

            // Try exception handling
            var caught = false
            try {
                test.throwException()
            } catch (ex: UnsupportedOperationException) {
                System.err.println("\tExpected exception (exception log should ONLY be on the object impl side).")
                caught = true
            }
            Assert.assertTrue(caught)


            // Non-blocking call tests
            // Non-blocking call tests
            // Non-blocking call tests
            System.err.println("I'm currently async: ${remoteObject.async}")

            remoteObject.async = true


            // calls that ignore the return value
            test.moo("Meow")
            // Non-blocking call that ignores the return value
            Assert.assertEquals(0, test.id().toLong())


            // exceptions are still dealt with properly
            test.moo("Baa")

            caught = false
            try {
                test.throwException()
            } catch (ex: IllegalStateException) {
                System.err.println("\tExpected exception (exception log should ONLY be on the object impl side).")
                caught = true
            }
            // exceptions are not caught when async = true!
            Assert.assertFalse(caught)


            // Call will time out if non-blocking isn't working properly
            test.moo("Mooooooooo", 4000)


            // should wait for a small time
            remoteObject.async = false
            remoteObject.responseTimeout = 6000
            println("You should see this 2 seconds before")
            val slow = test.slow()
            println("...This")
            Assert.assertEquals(slow.toDouble(), 123.0, 0.0001)


            // Test sending a reference to a remote object.
            val m = MessageWithTestCow(test)
            m.number = 678
            m.text = "sometext"
            connection.send(m)
            println("Finished tests")
        }

        fun register(manager: NetworkSerializationManager) {
            manager.register(Any::class.java) // Needed for Object#toString, hashCode, etc.
            manager.register(TestCow::class.java)
            manager.register(MessageWithTestCow::class.java)
            manager.register(UnsupportedOperationException::class.java)
        }
    }

    @Test
    @Throws(SecurityException::class, IOException::class, InterruptedException::class)
    fun rmiNetworkGlobal() {
        rmiGlobal()

        // have to reset the object ID counter
        TestCowImpl.ID_COUNTER.set(1)
        Thread.sleep(2000L)
    }

    @Test
    @Throws(SecurityException::class, IOException::class, InterruptedException::class)
    fun rmiNetworkConnection() {
        rmi()

        // have to reset the object ID counter
        TestCowImpl.ID_COUNTER.set(1)
        Thread.sleep(2000L)
    }

    @Test
    @Throws(SecurityException::class, IOException::class, InterruptedException::class)
    fun rmiIPC() {
        rmi { configuration ->
            if (configuration is ServerConfiguration) {
                configuration.listenIpAddress = LOOPBACK
            }
        }

        // have to reset the object ID counter
        TestCowImpl.ID_COUNTER.set(1)
        Thread.sleep(2000L)
    }

    @Throws(SecurityException::class, IOException::class)
    fun rmi(config: (Configuration) -> Unit = {}) {
        run {
            val configuration = serverConfig()
            config(configuration)
            register(configuration.serialization)

            // for Client -> Server RMI
            configuration.serialization.registerRmi(TestCow::class.java, TestCowImpl::class.java)

            val server = Server<Connection>(configuration)
            addEndPoint(server)

            server.bind(false)

            server.onMessage<MessageWithTestCow> { connection, m ->
                System.err.println("Received finish signal for test for: Client -> Server")
                val `object` = m.testCow
                val id = `object`.id()
                Assert.assertEquals(1, id.toLong())
                System.err.println("Finished test for: Client -> Server")
                System.err.println("Starting test for: Server -> Client")

                // normally this is in the 'connected', but we do it here, so that it's more linear and easier to debug
                connection.createObject<TestCow> { remoteObject ->
                    System.err.println("Running test for: Server -> Client")
                    runTests(connection, remoteObject, 2)
                    System.err.println("Done with test for: Server -> Client")
                }
            }
        }

        run {
            val configuration = clientConfig()
            config(configuration)
            register(configuration.serialization)

            // for Server -> Client RMI
            configuration.serialization.registerRmi(TestCow::class.java, TestCowImpl::class.java)

            val client = Client<Connection>(configuration)
            addEndPoint(client)

            client.onConnect { connection ->
                connection.createObject<TestCow> { remoteObject ->
                    System.err.println("Running test for: Client -> Server")
                    runTests(connection, remoteObject, 1)
                    System.err.println("Done with test for: Client -> Server")
                }
            }

            client.onMessage<MessageWithTestCow> { _, m ->
                System.err.println("Received finish signal for test for: Client -> Server")
                val `object` = m.testCow
                val id = `object`.id()
                Assert.assertEquals(2, id.toLong())
                System.err.println("Finished test for: Client -> Server")
                stopEndPoints(2000)
            }

            runBlocking {
                client.connect(LOOPBACK)
            }
        }

        waitForThreads()
    }

    @Throws(SecurityException::class, IOException::class)
    fun rmiGlobal(config: (Configuration) -> Unit = {}) {
        run {
            val configuration = serverConfig()
            config(configuration)
            register(configuration.serialization)

            // for Client -> Server RMI
            configuration.serialization.registerRmi(TestCow::class.java, TestCowImpl::class.java)

            val server = Server<Connection>(configuration)
            addEndPoint(server)

            server.bind(false)

            server.onMessage<MessageWithTestCow> { connection, m ->
                System.err.println("Received finish signal for test for: Client -> Server")

                val `object` = m.testCow
                val id = `object`.id()

                Assert.assertEquals(1, id.toLong())

                System.err.println("Finished test for: Client -> Server")

                // normally this is in the 'connected', but we do it here, so that it's more linear and easier to debug
                connection.createObject<TestCow> { remoteObject ->
                    System.err.println("Running test for: Server -> Client")
                    runTests(connection, remoteObject, 2)
                    System.err.println("Done with test for: Server -> Client")
                }
            }
        }

        run {
            val configuration = clientConfig()
            config(configuration)
            register(configuration.serialization)

            // for Server -> Client RMI
            configuration.serialization.registerRmi(TestCow::class.java, TestCowImpl::class.java)
            val client = Client<Connection>(configuration)
            addEndPoint(client)

            client.onMessage<MessageWithTestCow> { _, m ->
                System.err.println("Received finish signal for test for: Client -> Server")
                val `object` = m.testCow
                val id = `object`.id()
                Assert.assertEquals(2, id.toLong())
                System.err.println("Finished test for: Client -> Server")
                stopEndPoints(2000)
            }

            runBlocking {
                client.connect(LOOPBACK)

                System.err.println("Starting test for: Client -> Server")

                // this creates a GLOBAL object on the server (instead of a connection specific object)
                client.createObject<TestCow> { remoteObject ->
                    System.err.println("Running test for: Client -> Server")
                    runTests(client.getConnection(), remoteObject, 1)
                    System.err.println("Done with test for: Client -> Server")
                }
            }
        }

        waitForThreads()
    }
}
