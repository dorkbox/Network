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
package dorkboxTest.network.memoryTest

import ch.qos.logback.classic.Level
import dorkbox.network.Client
import dorkbox.network.Server
import dorkbox.network.connection.Connection
import dorkbox.network.rmi.RemoteObject
import dorkboxTest.network.BaseTest
import kotlinx.coroutines.runBlocking
import org.junit.Ignore
import org.junit.Test
import java.util.concurrent.atomic.*

@Ignore
class MemoryTest : BaseTest() {
    private val counter = AtomicLong(0)

    init {
        // the logger cannot keep-up if it's on trace
        setLogLevel(Level.DEBUG)
    }

    @Test
    fun runForeverIpcAsyncNormal() {
        runBlocking {
            val RMI_ID = 12251

            run {
                val configuration = serverConfig()
                configuration.serialization.rmi.register(TestObject::class.java, TestObjectImpl::class.java)

                val server = Server<Connection>(configuration)
                server.rmiGlobal.save(TestObjectImpl(counter), RMI_ID)
                server.bind()
            }


            run {
                val client = Client<Connection>(clientConfig())
                client.onConnect {
                    val remoteObject = rmi.getGlobal<TestObject>(RMI_ID)
                    val obj = RemoteObject.cast(remoteObject)
                    obj.async = true

                    var i = 0L
                    while (true) {
                        i++
                        try {
                            remoteObject.setOther(i)
                        } catch (e: Exception) {
                            logger.error("Timeout when calling RMI method")
                            e.printStackTrace()
                        }
                    }
                }

                client.connect()
            }

            Thread.sleep(Long.MAX_VALUE)
        }
    }

    @Test
    fun runForeverIpcAsyncSuspend() {
        val RMI_ID = 12251

        runBlocking {
            run {
                val configuration = serverConfig()
                configuration.serialization.rmi.register(TestObject::class.java, TestObjectImpl::class.java)

                val server = Server<Connection>(configuration)
                server.rmiGlobal.save(TestObjectImpl(counter), RMI_ID)
                server.bind()
            }


            run {
                val client = Client<Connection>(clientConfig())
                client.onConnect {
                    val remoteObject = rmi.getGlobal<TestObject>(RMI_ID)
                    val obj = RemoteObject.cast(remoteObject)
                    obj.async = true

                    var i = 0L
                    while (true) {
                        i++
                        try {
                            remoteObject.setOtherSus(i)
                        } catch (e: Exception) {
                            logger.error("Timeout when calling RMI method")
                            e.printStackTrace()
                        }
                    }
                }

                client.connect()
            }

            Thread.sleep(Long.MAX_VALUE)
        }
    }

    @Test
    fun runForeverIpc() {
        runBlocking {
            run {
                val configuration = serverConfig()

                val server = Server<Connection>(configuration)

                server.onMessage<Long> { testObject ->
                    send(testObject+1)
                }

                server.bind()
            }


            run {
                val client = Client<Connection>(clientConfig())

                client.onMessage<Long> { testObject ->
                    send(testObject+1)
                }

                client.onConnect {
                    send(0L)
                }

                client.connect()
            }

            Thread.sleep(Long.MAX_VALUE)
        }
    }

    private interface TestObject {
        fun setOther(value: Long): Boolean
        suspend fun setOtherSus(value: Long): Boolean
    }

    private class TestObjectImpl(private val counter: AtomicLong) : TestObject {
        @Override
        override fun setOther(value: Long): Boolean {
            counter.getAndIncrement()
            return true
        }

        @Override
        override suspend fun setOtherSus(value: Long): Boolean {
            counter.getAndIncrement()
            return true
        }
    }
}
