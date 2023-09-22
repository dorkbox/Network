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
import dorkbox.network.connection.session.SessionClient
import dorkbox.network.connection.session.SessionConnection
import dorkbox.network.connection.session.SessionServer
import dorkbox.network.rmi.RemoteObject
import dorkboxTest.network.rmi.cows.MessageWithTestCow
import dorkboxTest.network.rmi.cows.TestCow
import dorkboxTest.network.rmi.cows.TestCowImpl
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Assert
import org.junit.Test

class SessionReconnectTest: BaseTest() {
    @Test
    fun rmiReconnectSessions() {
        val server = run {
            val configuration = serverConfig()

            configuration.serialization.rmi.register(TestCow::class.java, TestCowImpl::class.java)
            configuration.serialization.register(MessageWithTestCow::class.java)
            configuration.serialization.register(UnsupportedOperationException::class.java)

            // for Client -> Server RMI
            configuration.serialization.rmi.register(TestCow::class.java, TestCowImpl::class.java)
            configuration.enableSessionManagement = true

            val server = SessionServer<SessionConnection>(configuration)

            addEndPoint(server)

            server.onMessage<MessageWithTestCow> { m ->
                server.logger.error("Received finish signal for test for: Client -> Server")

                val `object` = m.testCow
                val id = `object`.id()

                Assert.assertEquals(23, id)

                server.logger.error("Finished test for: Client -> Server")

                // we are going to try to remotely access this again!
//                rmi.delete(23)

                // `object` is still a reference to the object!
                // so we don't want to pass that back -- so pass back a new one
                send(MessageWithTestCow(TestCowImpl(1)))
            }

            server
        }

        val client = run {
            val configuration = clientConfig()


            val client = SessionClient<SessionConnection>(configuration)

            addEndPoint(client)

            var rmiId = 0

            client.onConnect {
                logger.error("Connecting")

                rmi.create<TestCow>(23) {
                    rmiId = it
                    moo("Client -> Server")

                    val m = MessageWithTestCow(this)
                    m.number = 678
                    m.text = "sometext"
                    this@onConnect.send(m)
                }
            }

            client.onMessage<MessageWithTestCow> { _ ->
                val obj = rmi.get<TestCow>(rmiId)

                GlobalScope.launch {
                    delay(4000)

                    obj.moo("DELAYED AND NOT CRASHED!")

                    stopEndPoints()
                }

                client.close(false)
                client.connectIpc()
            }

            client
        }

        server.bindIpc()
        client.connectIpc()

        waitForThreads()
    }

    @Test
    fun rmiReconnectReplayMessages() {
        val server = run {
            val configuration = serverConfig()

            configuration.serialization.rmi.register(TestCow::class.java, TestCowImpl::class.java)
            configuration.serialization.register(MessageWithTestCow::class.java)
            configuration.serialization.register(UnsupportedOperationException::class.java)

            // for Client -> Server RMI
            configuration.serialization.rmi.register(TestCow::class.java, TestCowImpl::class.java)
            configuration.enableSessionManagement = true

            val server = SessionServer<SessionConnection>(configuration)

            addEndPoint(server)

            server.onMessage<MessageWithTestCow> { m ->
                server.logger.error("Received finish signal for test for: Client -> Server")

                val `object` = m.testCow
                val id = `object`.id()

                Assert.assertEquals(23, id)

                server.logger.error("Finished test for: Client -> Server")

                // we are going to try to remotely access this again!
//                rmi.delete(23)

                // `object` is still a reference to the object!
                // so we don't want to pass that back -- so pass back a new one
                send(MessageWithTestCow(TestCowImpl(1)))
            }

            server
        }

        val client = run {
            val configuration = clientConfig()


            val client = SessionClient<SessionConnection>(configuration)

            addEndPoint(client)

            var rmiId = 0

            client.onConnect {
                logger.error("Connecting")

                rmi.create<TestCow>(23) {
                    rmiId = it
                    moo("Client -> Server")

                    val m = MessageWithTestCow(this)
                    m.number = 678
                    m.text = "sometext"
                    this@onConnect.send(m)
                }
            }

            client.onMessage<MessageWithTestCow> { _ ->
                val obj = rmi.get<TestCow>(rmiId)

                client.close(false)

                RemoteObject.cast(obj).responseTimeout = 50_000

                GlobalScope.launch {
                    delay(1000) // must be shorter than the RMI timeout for our tests

                    client.connectIpc()
                    stopEndPoints()
                }

                // this is SYNC, so it waits for a response!
                obj.moo("DELAYED AND NOT CRASHED!")
            }

            client
        }

        server.bindIpc()
        client.connectIpc()

        waitForThreads()
    }

    @Test
    fun rmiReconnectSessionsFail() {

        var rmiId = 0

        val server = run {
            val configuration = serverConfig()

            configuration.serialization.rmi.register(TestCow::class.java, TestCowImpl::class.java)
            configuration.serialization.register(MessageWithTestCow::class.java)
            configuration.serialization.register(UnsupportedOperationException::class.java)

            // for Client -> Server RMI
            configuration.serialization.rmi.register(TestCow::class.java, TestCowImpl::class.java)
            configuration.enableSessionManagement = true

            val server = SessionServer<SessionConnection>(configuration)

            addEndPoint(server)

            server.onMessage<MessageWithTestCow> { m ->
                server.logger.error("Received finish signal for test for: Client -> Server")

                val `object` = m.testCow
                val id = `object`.id()

                Assert.assertEquals(23, id)

                server.logger.error("Finished test for: Client -> Server")


                rmi.delete(rmiId)


                // `object` is still a reference to the object!
                // so we don't want to pass that back -- so pass back a new one
                send(MessageWithTestCow(TestCowImpl(1)))
            }

            server
        }

        val client = run {
            val configuration = clientConfig()

            val client = SessionClient<SessionConnection>(configuration)

            addEndPoint(client)

            var firstTime = true

            client.onConnect {
                if (firstTime) {
                    firstTime = false
                    logger.error("Connecting")

                    rmi.create<TestCow>(23) {
                        rmiId = it
                        moo("Client -> Server")

                        val m = MessageWithTestCow(this)
                        m.number = 678
                        m.text = "sometext"
                        this@onConnect.send(m)
                    }
                }
            }

            client.onMessage<MessageWithTestCow> { _ ->
                val obj = rmi.get<TestCow>(rmiId)

                GlobalScope.launch {
                    delay(4000)

                    try {
                        val o2 = RemoteObject.cast(obj)
                        o2.sync {
                            obj.moo("DELAYED AND NOT CRASHED!")
                            Assert.fail(".moo() should throw an exception, the backing RMI object doesn't exist!")
                        }
                    }
                    catch (ignored: Exception) {
                    }

                    stopEndPoints()
                }

                client.close(false)
                client.connectIpc()
            }

            client
        }

        server.bindIpc()
        client.connectIpc()

        waitForThreads()
    }

    @Test
    fun rmiReconnectSessionsFail2() {

        var rmiId = 0

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


                rmi.delete(rmiId)


                // `object` is still a reference to the object!
                // so we don't want to pass that back -- so pass back a new one
                send(MessageWithTestCow(TestCowImpl(1)))
            }

            server
        }

        val client = run {
            val configuration = clientConfig()


            val client = Client<Connection>(configuration)

            addEndPoint(client)

            var firstTime = true

            client.onConnect {
                if (firstTime) {
                    firstTime = false
                    logger.error("Connecting")

                    rmi.create<TestCow>(23) {
                        rmiId = it
                        moo("Client -> Server")

                        val m = MessageWithTestCow(this)
                        m.number = 678
                        m.text = "sometext"
                        this@onConnect.send(m)
                    }
                }
            }

            client.onMessage<MessageWithTestCow> { _ ->
                val obj = rmi.get<TestCow>(rmiId)

                GlobalScope.launch {
                    delay(4000)

                    try {
                        val o2 = RemoteObject.cast(obj)
                        o2.sync {
                            obj.moo("CRASHED!")
                            Assert.fail(".moo() should throw an exception, the backing RMI object doesn't exist!")
                        }
                    }
                    catch (e: Exception) {
                        logger.error("Successfully caught error!")
                    }

                    stopEndPoints()
                }

                client.close(false)
                client.connectIpc()
            }

            client
        }

        server.bindIpc()
        client.connectIpc()

        waitForThreads()
    }
}
