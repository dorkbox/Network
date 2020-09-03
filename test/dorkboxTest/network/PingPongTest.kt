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
package dorkboxTest.network

import dorkbox.network.Client
import dorkbox.network.Server
import dorkbox.network.connection.Connection
import dorkbox.network.serialization.Serialization
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class PingPongTest : BaseTest() {
    @Volatile
    private var fail: String? = null
    var tries = 1000

    @Test
    fun pingPong() {
        fail = "Data not received."
        val data = Data()
        populateData(data)

        run {
            val configuration = serverConfig()
            register(configuration.serialization)


            val server: Server<Connection> = Server(configuration)
            addEndPoint(server)
            server.bind()

            server.onError { _, throwable ->
                fail = "Error during processing. $throwable"
            }

            server.onConnect { connection ->
                server.forEachConnection { connection ->
                    connection.logger.error("server connection: $connection")
                }
            }

            server.onMessage<Data> { connection, message ->
                connection.send(message)
            }
        }


        run {
            val config = clientConfig()

            val client: Client<Connection> = Client(config)
            addEndPoint(client)


            client.onConnect { connection ->
                client.forEachConnection { connection ->
                    connection.logger.error("client connection: $connection")
                }

                fail = null
                connection.send(data)
            }

            client.onError { connection, throwable ->
                fail = "Error during processing. $throwable"
                throwable.printStackTrace()
            }

            val counter = AtomicInteger(0)
            client.onMessage<Data> { connection, message ->
                if (counter.getAndIncrement() <= tries) {
                    connection.send(data)
                } else {
                    connection.logger.error("done.")
                    connection.logger.error("Ran $tries times")
                    stopEndPoints()
                }
            }

            runBlocking {
                client.connect(LOOPBACK)
            }
        }


        waitForThreads()


        if (fail != null) {
            Assert.fail(fail)
        }
    }

    private fun register(manager: Serialization) {
        manager.register(Data::class.java)
    }

    class Data {
        var string: String? = null
        var strings = arrayOf<String>()

        var ints = IntArray(0)
        var shorts = ShortArray(0)
        var floats = FloatArray(0)
        var doubles = DoubleArray(0)
        var longs = LongArray(0)
        var bytes = ByteArray(0)
        var chars = CharArray(0)
        var booleans = BooleanArray(0)

        var Ints = Array(0) { 0 }
        var Shorts = Array(0) { 0.toShort() }
        var Floats = Array(0) { 0F }
        var Doubles = Array(0) { 0.0 }
        var Longs = Array(0) { 0L }
        var Bytes = Array(0) { 0.toByte() }
        var Chars = Array(0) { 'a' }
        var Booleans = Array(0) { false }

        override fun hashCode(): Int {
            val prime = 31
            var result = 1
            result = prime * result + Booleans.contentHashCode()
            result = prime * result + Bytes.contentHashCode()
            result = prime * result + Chars.contentHashCode()
            result = prime * result + Doubles.contentHashCode()
            result = prime * result + Floats.contentHashCode()
            result = prime * result + Ints.contentHashCode()
            result = prime * result + Longs.contentHashCode()
            result = prime * result + Shorts.contentHashCode()
            result = prime * result + booleans.contentHashCode()
            result = prime * result + bytes.contentHashCode()
            result = prime * result + chars.contentHashCode()
            result = prime * result + doubles.contentHashCode()
            result = prime * result + floats.contentHashCode()
            result = prime * result + ints.contentHashCode()
            result = prime * result + longs.contentHashCode()
            result = prime * result + shorts.contentHashCode()
            result = prime * result + if (string == null) 0 else string.hashCode()
            result = prime * result + strings.contentHashCode()
            return result
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) {
                return true
            }

            if (other == null) {
                return false
            }

            if (javaClass != other.javaClass) {
                return false
            }

            val other = other as Data
            if (!Booleans.contentEquals(other.Booleans)) {
                return false
            }
            if (!Bytes.contentEquals(other.Bytes)) {
                return false
            }
            if (!Chars.contentEquals(other.Chars)) {
                return false
            }
            if (!Doubles.contentEquals(other.Doubles)) {
                return false
            }
            if (!Floats.contentEquals(other.Floats)) {
                return false
            }
            if (!Ints.contentEquals(other.Ints)) {
                return false
            }
            if (!Longs.contentEquals(other.Longs)) {
                return false
            }
            if (!Shorts.contentEquals(other.Shorts)) {
                return false
            }
            if (!booleans.contentEquals(other.booleans)) {
                return false
            }
            if (!bytes.contentEquals(other.bytes)) {
                return false
            }
            if (!chars.contentEquals(other.chars)) {
                return false
            }
            if (!doubles.contentEquals(other.doubles)) {
                return false
            }
            if (!floats.contentEquals(other.floats)) {
                return false
            }
            if (!ints.contentEquals(other.ints)) {
                return false
            }
            if (!longs.contentEquals(other.longs)) {
                return false
            }
            if (!shorts.contentEquals(other.shorts)) {
                return false
            }
            if (string == null) {
                if (other.string != null) {
                    return false
                }
            } else if (string != other.string) {
                return false
            }

            if (!strings.contentEquals(other.strings)) {
                return false
            }

            return true
        }

        override fun toString(): String {
            return "Data"
        }
    }

    companion object {
        private fun populateData(data: Data) {
            val buffer = StringBuilder(3001)
            for (i in 0..2999) {
                buffer.append('a')
            }
            data.string = buffer.toString()
            data.strings = arrayOf("abcdefghijklmnopqrstuvwxyz0123456789", "", "!@#$", "�����")
            data.ints = intArrayOf(-1234567, 1234567, -1, 0, 1, Int.MAX_VALUE, Int.MIN_VALUE)
            data.shorts = shortArrayOf(-12345, 12345, -1, 0, 1, Short.MAX_VALUE, Short.MIN_VALUE)
            data.floats = floatArrayOf(0f, -0f, 1f, -1f, 123456f, -123456f, 0.1f, 0.2f, -0.3f, Math.PI.toFloat(), Float.MAX_VALUE, Float.MIN_VALUE)
            data.doubles = doubleArrayOf(0.0, -0.0, 1.0, -1.0, 123456.0, -123456.0, 0.1, 0.2, -0.3, Math.PI, Double.MAX_VALUE, Double.MIN_VALUE)
            data.longs = longArrayOf(0, -0, 1, -1, 123456, -123456, 99999999999L, -99999999999L, Long.MAX_VALUE, Long.MIN_VALUE)
            data.bytes = byteArrayOf(-123, 123, -1, 0, 1, Byte.MAX_VALUE, Byte.MIN_VALUE)
            data.chars = charArrayOf(32345.toChar(), 12345.toChar(), 0.toChar(), 1.toChar(), 63.toChar(), Character.MAX_VALUE, Character.MIN_VALUE)
            data.booleans = booleanArrayOf(true, false)
            data.Ints = arrayOf(-1234567, 1234567, -1, 0, 1, Int.MAX_VALUE, Int.MIN_VALUE)
            data.Shorts = arrayOf(-12345, 12345, -1, 0, 1, Short.MAX_VALUE, Short.MIN_VALUE)
            data.Floats = arrayOf(0f, -0f, 1.0f, -1f, 123456f, -123456f, 0.1f, 0.2f, -0.3f, Math.PI.toFloat(), Float.MAX_VALUE, Float.MIN_VALUE)
            data.Doubles = arrayOf(0.0, -0.0, 1.0, -1.0, 123456.0, -123456.0, 0.1, 0.2, -0.3, Math.PI, Double.MAX_VALUE, Double.MIN_VALUE)
            data.Longs = arrayOf(0L, -0L, 1L, -1L, 123456L, -123456L, 99999999999L, -99999999999L, Long.MAX_VALUE, Long.MIN_VALUE)
            data.Bytes = arrayOf(-123, 123, -1, 0, 1, Byte.MAX_VALUE, Byte.MIN_VALUE)
            data.Chars = arrayOf(32345.toChar(), 12345.toChar(), 0.toChar(), 1.toChar(), 63.toChar(), Character.MAX_VALUE, Character.MIN_VALUE)
            data.Booleans = arrayOf(true, false)
        }
    }
}
