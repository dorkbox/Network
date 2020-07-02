/*
 * Copyright 2019 dorkbox, llc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkbox.network.rmi

import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import dorkbox.network.Configuration
import dorkbox.network.connection.*
import dorkbox.network.serialization.NetworkSerializationManager
import dorkbox.network.store.NullSettingsStore
import io.netty.buffer.ByteBuf
import org.slf4j.LoggerFactory
import java.io.File
import javax.crypto.SecretKey

class NopRmiConnection : Connection_ {
    val logger = LoggerFactory.getLogger("RMI_NO_OP")
    val rmiBridgeUnnecessaryMaybe = RmiServer(logger, true)

    val config = Configuration().apply {
        aeronLogDirectory = File("")
        settingsStore = NullSettingsStore()
        serialization = object : NetworkSerializationManager {
            override fun <Iface, Impl : Iface> registerRmi(ifaceClass: Class<Iface>, implClass: Class<Impl>): NetworkSerializationManager {
                return this
            }

            override fun takeKryo(): KryoExtra {
                TODO("Not yet implemented")
            }

//            override fun readWithCompression(connection: Connection_, length: Int): Any {
//                return false
//            }

            override fun <T> getRmiImpl(iFace: Class<T>): Class<T> {
                TODO("Not yet implemented")
            }

            override fun readFullClassAndObject(input: Input?): Any {
                return false
            }

//            override fun write(connection: Connection_, message: Any) {
//            }

            override fun write(buffer: ByteBuf, message: Any) {
            }

            override fun initialized(): Boolean {
                return false
            }

            override fun getMethods(classID: Int): Array<CachedMethod> {
                TODO("Not yet implemented")
            }

            override fun finishInit(endPointClass: Class<*>) {
            }

//            override fun writeWithCompression(connection: Connection_, message: Any) {
//            }

            override fun getKryoRegistrationDetails(): ByteArray {
                return ByteArray(0)
            }

            override fun writeFullClassAndObject(output: Output?, value: Any?) {
            }

            override fun <T> register(clazz: Class<T>): NetworkSerializationManager {
                return this
            }

            override fun <T> register(clazz: Class<T>, id: Int): NetworkSerializationManager {
                return this
            }

            override fun <T> register(clazz: Class<T>, serializer: Serializer<T>): NetworkSerializationManager {
                return this
            }

            override fun <T> register(clazz: Class<T>, serializer: Serializer<T>, id: Int): NetworkSerializationManager {
                return this
            }

            override fun verifyKryoRegistration(bytes: ByteArray): Boolean {
                return false
            }

//            override fun writeWithCrypto(connection: Connection_, message: Any) {
//            }

            override fun returnKryo(kryo: KryoExtra) {
            }

//            override fun read(connection: Connection_, length: Int): Any {
//                return false
//            }

            override fun read(buffer: ByteBuf, length: Int): Any {
                return false
            }

//            override fun readWithCrypto(connection: Connection_, length: Int): Any {
//                return false
//            }
        }
    }

    val connectionManager2: ConnectionManager<Connection_> = ConnectionManager(logger, config)
    val rmiEndpoint: EndPoint<Connection_> = object : EndPoint<Connection_>(Any::class.java, config) {
        override val connectionManager: ConnectionManager<Connection_> = connectionManager2
        override fun isConnected(): Boolean { return false }
    }

    val con = object : Connection_ {
        override fun pollSubscriptions(): Int {
            return 0
        }

        override fun rmiSupport(): ConnectionRmiSupport {
            TODO("Not yet implemented")
        }

        override fun nextGcmSequence(): Long {
            TODO("Not yet implemented")
        }

        override fun cryptoKey(): SecretKey {
            TODO("Not yet implemented")
        }

        override fun isExpired(now: Long): Boolean {
            return false
        }

        override fun isClosed(): Boolean {
            return true
        }

        override fun endPoint(): EndPoint<*> {
            TODO("Not yet implemented")
        }

        override fun hasRemoteKeyChanged(): Boolean {
            TODO("Not yet implemented")
        }

        override val subscriptionPort = 0
        override val publicationPort = 0

        override val remoteAddress = "0.0.0.0"
        override val remoteAddressInt = 0
        override val streamId = 0
        override val sessionId = 0
        override val isLoopback = false

        override val isIPC = false
        override val isNetwork = false

        override suspend fun send(message: Any) {
            TODO("Not yet implemented")
        }

        override suspend fun send(message: Any, priority: Byte) {
            TODO("Not yet implemented")
        }

        override suspend fun ping(): Ping {
            TODO("Not yet implemented")
        }

        override fun close() {
            TODO("Not yet implemented")
        }

        override suspend fun <Iface> createRemoteObject(interfaceClass: Class<Iface>, callback: RemoteObjectCallback<Iface>) {
            TODO("Not yet implemented")
        }

        override suspend fun <Iface> getRemoteObject(objectId: Int, callback: RemoteObjectCallback<Iface>) {
            TODO("Not yet implemented")
        }
    }

    val rmiSUpport = ConnectionRmiSupport(rmiBridgeUnnecessaryMaybe)

    init {
        println("CREATED RMI NO OP")
    }

    override fun hasRemoteKeyChanged(): Boolean {
        return false
    }

    override val subscriptionPort = 0
    override val publicationPort = 0
    override val remoteAddress = ""
    override val remoteAddressInt = 0

    override val isLoopback = false
    override val isIPC = false
    override val isNetwork = false

    override fun endPoint(): EndPoint<*> {
        return rmiEndpoint
    }

    override suspend fun send(message: Any) {}
    override suspend fun send(message: Any, priority: Byte) {}

    override suspend fun ping(): Ping {
        TODO("Not yet implemented")
    }

    override fun close() {}
    override suspend fun <Iface> createRemoteObject(interfaceClass: Class<Iface>, callback: RemoteObjectCallback<Iface>) {}
    override suspend fun <Iface> getRemoteObject(objectId: Int, callback: RemoteObjectCallback<Iface>) {}
    override fun nextGcmSequence(): Long {
        return 0
    }

    override fun cryptoKey(): SecretKey {
        TODO("Not yet implemented")
    }

    override fun pollSubscriptions(): Int {
        return 0
    }

    override fun isExpired(now: Long): Boolean {
        return false
    }

    override val streamId: Int = 0
    override val sessionId: Int = 0

    override fun rmiSupport(): ConnectionRmiSupport {
        return rmiSUpport
    }

    override fun isClosed(): Boolean {
        return false
    }
}
