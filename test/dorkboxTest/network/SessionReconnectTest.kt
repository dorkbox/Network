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
import dorkboxTest.network.rmi.cows.TestCow
import dorkboxTest.network.rmi.cows.TestCowImpl
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Assert
import org.junit.Test


class MessageToContinue

class SessionReconnectTest: BaseTest() {
    @Test
    fun rmiReconnectSessions() {
        val server = run {
            val configuration = serverConfig()

            configuration.serialization.rmi.register(TestCow::class.java, TestCowImpl::class.java)
            configuration.serialization.register(MessageToContinue::class.java)
            configuration.serialization.register(UnsupportedOperationException::class.java)

            // for Client -> Server RMI
            configuration.serialization.rmi.register(TestCow::class.java, TestCowImpl::class.java)
            configuration.enableSessionManagement = true

            val server = SessionServer<SessionConnection>(configuration)

            addEndPoint(server)

            server.onMessage<MessageToContinue> { m ->
                send(MessageToContinue())
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

                    this@onConnect.send(MessageToContinue())
                }
            }

            client.onMessage<MessageToContinue> { _ ->
                val get = rmi.get<TestCow>(rmiId)
                RemoteObject.cast(get).responseTimeout = 50_000

                GlobalScope.launch {
                    delay(4000)

                    get.moo("DELAYED AND NOT CRASHED!")

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
            configuration.serialization.register(MessageToContinue::class.java)
            configuration.serialization.register(UnsupportedOperationException::class.java)

            // for Client -> Server RMI
            configuration.serialization.rmi.register(TestCow::class.java, TestCowImpl::class.java)
            configuration.enableSessionManagement = true

            val server = SessionServer<SessionConnection>(configuration)

            addEndPoint(server)

            server.onMessage<MessageToContinue> { m ->
                send(MessageToContinue())
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

                    this@onConnect.send(MessageToContinue())
                }
            }

            client.onMessage<MessageToContinue> { _ ->
                logger.error("Starting reconnect bits")
                val obj = rmi.get<TestCow>(rmiId)

                // closing client
                client.close(false)
                client.waitForClose()

                logger.error("Getting object again. it should be the cached version")
                val cast = RemoteObject.cast(obj)
                cast.responseTimeout = 50_000

                GlobalScope.launch {
                    delay(1000) // must be shorter than the RMI timeout for our tests

                    client.connectIpc()

                    delay(1000) // give some time for the messages to go! If we close instantly, the return RMI message will fail
                    stopEndPoints()
                }

                // this is SYNC, so it waits for a response!
                try {
                    cast.async = false
                    obj.moo("DELAYED AND NOT CRASHED!") // will wait for a response
                }
                catch (e: Exception) {
                    e.printStackTrace()
                    e.cause?.printStackTrace()
                    Assert.fail(".moo() should not throw an exception, because it will succeed before the timeout")
                }
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
            configuration.serialization.register(MessageToContinue::class.java)
            configuration.serialization.register(UnsupportedOperationException::class.java)

            // for Client -> Server RMI
            configuration.serialization.rmi.register(TestCow::class.java, TestCowImpl::class.java)
            configuration.enableSessionManagement = true

            val server = SessionServer<SessionConnection>(configuration)

            addEndPoint(server)

            server.onMessage<MessageToContinue> { m ->
                rmi.delete(rmiId)

                // NOTE: if we send an RMI object, it will automatically be saved!
                send(MessageToContinue())
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
                        this@onConnect.send(MessageToContinue())
                    }
                }
            }

            client.onMessage<MessageToContinue> { _ ->
                val obj = rmi.get<TestCow>(rmiId)
                val o2 = RemoteObject.cast(obj)

                GlobalScope.launch {
                    delay(4000)

                    try {
                        o2.sync {
                            obj.moo("DELAYED AND NOT CRASHED!")
                            Assert.fail(".moo() should throw an timeout exception, the backing RMI object doesn't exist!")
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
            configuration.serialization.register(MessageToContinue::class.java)
            configuration.serialization.register(UnsupportedOperationException::class.java)

            // for Client -> Server RMI
            configuration.serialization.rmi.register(TestCow::class.java, TestCowImpl::class.java)

            val server = Server<Connection>(configuration)

            addEndPoint(server)

            server.onMessage<MessageToContinue> { m ->
                rmi.delete(rmiId)

                // NOTE: if we send an RMI object, it will automatically be saved!
                send(MessageToContinue())
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

                        this@onConnect.send(MessageToContinue())
                    }
                }
            }

            client.onMessage<MessageToContinue> { _ ->
                val obj = rmi.get<TestCow>(rmiId)
                val o2 = RemoteObject.cast(obj)
                o2.responseTimeout = 50_000

                GlobalScope.launch {
                    delay(4000)

                    try {
                        o2.sync {
                            obj.moo("CRASHED!")
                            Assert.fail(".moo() should throw an timeout exception, the backing RMI object doesn't exist!")
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
