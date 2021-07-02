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
import dorkbox.network.storage.types.MemoryStore
import dorkboxTest.network.BaseTest
import dorkboxTest.network.rmi.cows.MessageWithTestCow
import dorkboxTest.network.rmi.cows.TestBabyCowImpl
import dorkboxTest.network.rmi.cows.TestCow
import dorkboxTest.network.rmi.cows.TestCowImpl
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
        configuration.enableRemoteSignatureValidation = false
        configuration.settingsStore = MemoryStore.type() // don't want to persist anything on disk!

        configuration.serialization.rmi.register(TestCow::class.java, TestCowImpl::class.java)
        configuration.serialization.register(MessageWithTestCow::class.java)
        configuration.serialization.register(UnsupportedOperationException::class.java)

        configuration.serialization.register(TestBabyCowImpl::class.java)
        configuration.serialization.rmi.register(TestCow::class.java, TestCowImpl::class.java)

        val server = Server<Connection>(configuration)

        server.rmiGlobal.save(TestCowImpl(12123), 12123)

        server.onMessage<MessageWithTestCow> { m ->
            logger.error("Received finish signal for test for: Client -> Server")
            val `object` = m.testCow
            val id = `object`.id()
            Assert.assertEquals(124123, id.toLong())
            logger.error("Finished test for: Client -> Server")

//
//            System.err.println("Starting test for: Server -> Client")
//            connection.createObject<TestCow>(123) { rmiId, remoteObject ->
//                System.err.println("Running test for: Server -> Client")
//                RmiTest.runTests(connection, remoteObject, 123)
//                System.err.println("Done with test for: Server -> Client")
//            }
        }

        server.onMessage<TestCow> { test ->
            logger.error("Received test cow from client")
            // this object LIVES on the server.

            try {
                test.moo()
                Assert.fail("Should catch an exception!")
            } catch (e: Exception) {
            }

            // now test sending this object BACK to the client. The client SHOULD have the same RMI proxy object as before!
            send(test)

//            System.err.println("Starting test for: Server -> Client")
//            connection.createObject<TestCow>(123) { rmiId, remoteObject ->
//                System.err.println("Running test for: Server -> Client")
//                RmiTest.runTests(connection, remoteObject, 123)
//                System.err.println("Done with test for: Server -> Client")
//            }
        }

        server.bind()

        runBlocking {
            server.waitForClose()
        }
    }
}
