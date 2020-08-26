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
package dorkboxTest.network.rmi.multiJVM

import dorkbox.network.Server
import dorkbox.network.connection.Connection
import dorkboxTest.network.BaseTest
import dorkboxTest.network.rmi.RmiTest
import dorkboxTest.network.rmi.classes.MessageWithTestCow
import dorkboxTest.network.rmi.classes.TestBabyCow
import dorkboxTest.network.rmi.classes.TestCow
import dorkboxTest.network.rmi.classes.TestCowImpl
import dorkboxTest.network.rmi.multiJVM.TestClient.setup
import kotlinx.coroutines.runBlocking
import org.junit.Assert

/**
 *
 */
object TestServer {
    @JvmStatic
    fun main(args: Array<String>) {
        setup()

        val configuration = BaseTest.serverConfig()

        RmiTest.register(configuration.serialization)
        configuration.serialization.register(TestBabyCow::class.java)
        configuration.serialization.registerRmi(TestCow::class.java, TestCowImpl::class.java)
        configuration.enableRemoteSignatureValidation = false

        val server = Server<Connection>(configuration)

        server.onMessage<MessageWithTestCow> { connection, m ->
            System.err.println("Received finish signal for test for: Client -> Server")
            val `object` = m.testCow
            val id = `object`.id()
            Assert.assertEquals(124123, id.toLong())
            System.err.println("Finished test for: Client -> Server")

//
//            System.err.println("Starting test for: Server -> Client")
//            connection.createObject<TestCow>(123) { rmiId, remoteObject ->
//                System.err.println("Running test for: Server -> Client")
//                RmiTest.runTests(connection, remoteObject, 123)
//                System.err.println("Done with test for: Server -> Client")
//            }
        }

        server.onMessage<TestCow> { connection, test ->
            System.err.println("Received test cow from client")
            // this object LIVES on the server.

            try {
                test.moo()
                Assert.fail("Should catch an exception!")
            } catch (e: Exception) {
            }

            // now test sending this object BACK to the client. The client SHOULD have the same RMI proxy object as before!
            connection.send(test)

//            System.err.println("Starting test for: Server -> Client")
//            connection.createObject<TestCow>(123) { rmiId, remoteObject ->
//                System.err.println("Running test for: Server -> Client")
//                RmiTest.runTests(connection, remoteObject, 123)
//                System.err.println("Done with test for: Server -> Client")
//            }
        }

        runBlocking {
            server.bind(false)
        }
    }
}
