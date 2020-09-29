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
import dorkboxTest.network.BaseTest
import kotlinx.coroutines.runBlocking
import org.junit.Ignore
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

@Ignore
class MemoryTest : BaseTest() {
    private val counter = AtomicLong(0)

    private val RMI_ID = 12251

    init {
        // the logger cannot keep-up if it's on trace
        setLogLevel(Level.DEBUG)
    }

    @Test
    fun runForeverIpcAsyncNormal() {
        runBlocking {
            run {
                val configuration = serverConfig()
                configuration.serialization.registerRmi(TestObject::class.java, TestObjectImpl::class.java)

                val server = Server<Connection>(configuration)
                server.saveGlobalObject(TestObjectImpl(counter), RMI_ID)
                server.bind()
            }


            run {
                val client = Client<Connection>(clientConfig())
                client.onConnect { connection ->
                    val remoteObject = connection.getGlobalObject<TestObject>(RMI_ID)
                    val obj = remoteObject as RemoteObject
                    obj.async = true

                    var i = 0L
                    while (true) {
                        i++
                        try {
                            remoteObject.setOther(i)
                        } catch (e: Exception) {
                            connection.logger.error("Timeout when calling RMI method")
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
        runBlocking {
            run {
                val configuration = serverConfig()
                configuration.serialization.registerRmi(TestObject::class.java, TestObjectImpl::class.java)

                val server = Server<Connection>(configuration)
                server.saveGlobalObject(TestObjectImpl(counter), RMI_ID)
                server.bind()
            }


            run {
                val client = Client<Connection>(clientConfig())
                client.onConnect { connection ->
                    val remoteObject = connection.getGlobalObject<TestObject>(RMI_ID)
                    val obj = remoteObject as RemoteObject
                    obj.async = true

                    var i = 0L
                    while (true) {
                        i++
                        try {
                            remoteObject.setOtherSus(i)
                        } catch (e: Exception) {
                            connection.logger.error("Timeout when calling RMI method")
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

                server.onMessage<Long> { connection, testObject ->
                    connection.send(testObject+1)
                }

                server.bind()
            }


            run {
                val client = Client<Connection>(clientConfig())

                client.onMessage<Long> { connection, testObject ->
                    connection.send(testObject+1)
                }

                client.onConnect { connection ->
                    connection.send(0L)
                }

                client.connect()
            }

            Thread.sleep(Long.MAX_VALUE)
        }
    }

    @Test
    fun runForeverTestKryoPool() {
        val serialization = serverConfig().serialization

        // 17 to force a pool size change
        var kryo1: KryoExtra
        var kryo2: KryoExtra
        var kryo3: KryoExtra
        var kryo4: KryoExtra
        var kryo5: KryoExtra
        var kryo6: KryoExtra
        var kryo7: KryoExtra
        var kryo8: KryoExtra
        var kryo9: KryoExtra
        var kryo10: KryoExtra
        var kryo11: KryoExtra
        var kryo12: KryoExtra
        var kryo13: KryoExtra
        var kryo14: KryoExtra
        var kryo15: KryoExtra
        var kryo16: KryoExtra
        var kryo17: KryoExtra

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
        override fun setOther(aFloat: Long): Boolean {
            counter.getAndIncrement()
            return true
        }

        @Override
        override suspend fun setOtherSus(aFloat: Long): Boolean {
            counter.getAndIncrement()
            return true
        }
    }
}
