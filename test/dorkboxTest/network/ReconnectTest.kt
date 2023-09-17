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

package dorkboxTest.network

import dorkbox.network.Client
import dorkbox.network.Server
import dorkbox.network.connection.Connection
import dorkboxTest.network.rmi.RmiCommonTest
import dorkboxTest.network.rmi.cows.MessageWithTestCow
import dorkboxTest.network.rmi.cows.TestCow
import dorkboxTest.network.rmi.cows.TestCowImpl
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Test

class ReconnectTest: BaseTest() {
    @Test
    fun rmiReconnectPersistence() {
        val server = run {
            val configuration = serverConfig()

            configuration.serialization.rmi.register(TestCow::class.java, TestCowImpl::class.java)
            configuration.serialization.register(MessageWithTestCow::class.java)
            configuration.serialization.register(UnsupportedOperationException::class.java)

            // for Client -> Server RMI
            configuration.serialization.rmi.register(TestCow::class.java, TestCowImpl::class.java)

            val server = Server<Connection>(configuration)
            addEndPoint(server)

            server.onMessage<MessageWithTestCow> { m ->
                server.logger.error("Received finish signal for test for: Client -> Server")

                val `object` = m.testCow
                val id = `object`.id()

                Assert.assertEquals(23, id)

                server.logger.error("Finished test for: Client -> Server")

                rmi.delete(23)
                // `object` is still a reference to the object!
                // so we don't want to pass that back -- so pass back a new one
                send(MessageWithTestCow(TestCowImpl(1)))
            }

            server
        }

        val client = run {
            var firstRun = true
            val configuration = clientConfig()


            val client = Client<Connection>(configuration)
            addEndPoint(client)

            client.onConnect {
                rmi.create<TestCow>(23) {
                    client.logger.error("Running test for: Client -> Server")
                    runBlocking {
                        RmiCommonTest.runTests(this@onConnect, this@create, 23)
                    }
                    client.logger.error("Done with test for: Client -> Server")
                }
            }

            client.onMessage<MessageWithTestCow> { _ ->
                // check if 23 still exists (it should not)
                val obj = rmi.get<TestCow>(23)

                try {
                    obj.id()
                    Assert.fail(".id() should throw an exception, the backing RMI object doesn't exist!")
                } catch (e: Exception) {
                    // this is expected
                }

                if (firstRun) {
                    firstRun = false
                    client.close(false)
                    client.connectIpc()
                } else {
                    stopEndPoints()
                }
            }

            client
        }

        server.bindIpc()
        client.connectIpc()

        waitForThreads()
    }
}
