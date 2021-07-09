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
package dorkboxTest.network.memoryTest

import ch.qos.logback.classic.Level
import dorkbox.network.Client
import dorkbox.network.Server
import dorkbox.network.connection.Connection
import dorkbox.network.rmi.RemoteObject
import dorkbox.network.serialization.KryoExtra
import dorkbox.network.serialization.Serialization
import dorkboxTest.network.BaseTest
import kotlinx.coroutines.runBlocking
import org.junit.Ignore
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

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
                    val obj = remoteObject as RemoteObject
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

                client.connectIpc()
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
                    val obj = remoteObject as RemoteObject
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

    @Test
    fun runForeverTestKryoPool() {
        val serialization = serverConfig().serialization as Serialization<Connection>

        // 17 to force a pool size change
        var kryo1: KryoExtra<Connection>
        var kryo2: KryoExtra<Connection>
        var kryo3: KryoExtra<Connection>
        var kryo4: KryoExtra<Connection>
        var kryo5: KryoExtra<Connection>
        var kryo6: KryoExtra<Connection>
        var kryo7: KryoExtra<Connection>
        var kryo8: KryoExtra<Connection>
        var kryo9: KryoExtra<Connection>
        var kryo10: KryoExtra<Connection>
        var kryo11: KryoExtra<Connection>
        var kryo12: KryoExtra<Connection>
        var kryo13: KryoExtra<Connection>
        var kryo14: KryoExtra<Connection>
        var kryo15: KryoExtra<Connection>
        var kryo16: KryoExtra<Connection>
        var kryo17: KryoExtra<Connection>

        while (true) {
            kryo1 = serialization.takeKryo()
            kryo2 = serialization.takeKryo()
            kryo3 = serialization.takeKryo()
            kryo4 = serialization.takeKryo()
            kryo5 = serialization.takeKryo()
            kryo6 = serialization.takeKryo()
            kryo7 = serialization.takeKryo()
            kryo8 = serialization.takeKryo()
            kryo9 = serialization.takeKryo()
            kryo10 = serialization.takeKryo()
            kryo11 = serialization.takeKryo()
            kryo12 = serialization.takeKryo()
            kryo13 = serialization.takeKryo()
            kryo14 = serialization.takeKryo()
            kryo15 = serialization.takeKryo()
            kryo16 = serialization.takeKryo()
            kryo17 = serialization.takeKryo()


            serialization.returnKryo(kryo1)
            serialization.returnKryo(kryo2)
            serialization.returnKryo(kryo3)
            serialization.returnKryo(kryo4)
            serialization.returnKryo(kryo5)
            serialization.returnKryo(kryo6)
            serialization.returnKryo(kryo7)
            serialization.returnKryo(kryo8)
            serialization.returnKryo(kryo9)
            serialization.returnKryo(kryo10)
            serialization.returnKryo(kryo11)
            serialization.returnKryo(kryo12)
            serialization.returnKryo(kryo13)
            serialization.returnKryo(kryo14)
            serialization.returnKryo(kryo15)
            serialization.returnKryo(kryo16)
            serialization.returnKryo(kryo17)
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
