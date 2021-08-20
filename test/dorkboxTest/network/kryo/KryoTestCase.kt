/* Copyright (c) 2008-2018, Nathan Sweet
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
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE. */
package dorkboxTest.network.kryo

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import com.esotericsoftware.kryo.unsafe.UnsafeByteBufferInput
import com.esotericsoftware.kryo.unsafe.UnsafeByteBufferOutput
import com.esotericsoftware.kryo.unsafe.UnsafeInput
import com.esotericsoftware.kryo.unsafe.UnsafeOutput
import com.esotericsoftware.minlog.Log
import org.junit.Assert
import org.junit.Before
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Array
import java.nio.ByteBuffer

/** Convenience methods for round tripping objects.
 * @author Nathan Sweet
 */
abstract class KryoTestCase {
    public var kryo: Kryo? = null
    protected var output: Output? = null
    protected var input: Input? = null
    protected var object1: Any? = null
    protected var object2: Any? = null
    protected var supportsCopy = false

    interface BufferFactory {
        fun createOutput(os: OutputStream?): Output
        fun createOutput(os: OutputStream?, size: Int): Output
        fun createOutput(size: Int, limit: Int): Output
        fun createInput(os: InputStream?, size: Int): Input
        fun createInput(buffer: ByteArray): Input
    }

    @Before
    @Throws(Exception::class)
    fun setUp() {
        if (debug && Log.WARN) Log.warn("*** DEBUG TEST ***")
        kryo = Kryo()
    }

    /** @param length Pass Integer.MIN_VALUE to disable checking the length.
     */
    fun <T> roundTrip(length: Int, object1: T): T {
        val object2: T = roundTripWithBufferFactory(length, object1, object : BufferFactory {
            override fun createOutput(os: OutputStream?): Output {
                return Output(os)
            }

            override fun createOutput(os: OutputStream?, size: Int): Output {
                return Output(os, size)
            }

            override fun createOutput(size: Int, limit: Int): Output {
                return Output(size, limit)
            }

            override fun createInput(os: InputStream?, size: Int): Input {
                return Input(os, size)
            }

            override fun createInput(buffer: ByteArray): Input {
                return Input(buffer)
            }
        })
        if (debug) return object2
        roundTripWithBufferFactory(length, object1, object : BufferFactory {
            override fun createOutput(os: OutputStream?): Output {
                return ByteBufferOutput(os)
            }

            override fun createOutput(os: OutputStream?, size: Int): Output {
                return ByteBufferOutput(os, size)
            }

            override fun createOutput(size: Int, limit: Int): Output {
                return ByteBufferOutput(size, limit)
            }

            override fun createInput(os: InputStream?, size: Int): Input {
                return ByteBufferInput(os, size)
            }

            override fun createInput(buffer: ByteArray): Input {
                val byteBuffer = ByteBuffer.allocateDirect(buffer.size)
                byteBuffer.put(buffer).flip()
                return ByteBufferInput(byteBuffer)
            }
        })
        roundTripWithBufferFactory(length, object1, object : BufferFactory {
            override fun createOutput(os: OutputStream?): Output {
                return UnsafeOutput(os)
            }

            override fun createOutput(os: OutputStream?, size: Int): Output {
                return UnsafeOutput(os, size)
            }

            override fun createOutput(size: Int, limit: Int): Output {
                return UnsafeOutput(size, limit)
            }

            override fun createInput(os: InputStream?, size: Int): Input {
                return UnsafeInput(os, size)
            }

            override fun createInput(buffer: ByteArray): Input {
                return UnsafeInput(buffer)
            }
        })
        roundTripWithBufferFactory(length, object1, object : BufferFactory {
            override fun createOutput(os: OutputStream?): Output {
                return UnsafeByteBufferOutput(os)
            }

            override fun createOutput(os: OutputStream?, size: Int): Output {
                return UnsafeByteBufferOutput(os, size)
            }

            override fun createOutput(size: Int, limit: Int): Output {
                return UnsafeByteBufferOutput(size, limit)
            }

            override fun createInput(os: InputStream?, size: Int): Input {
                return UnsafeByteBufferInput(os, size)
            }

            override fun createInput(buffer: ByteArray): Input {
                val byteBuffer = ByteBuffer.allocateDirect(buffer.size)
                byteBuffer.put(buffer).flip()
                return UnsafeByteBufferInput(byteBuffer)
            }
        })
        return object2
    }

    /** @param length Pass Integer.MIN_VALUE to disable checking the length.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> roundTripWithBufferFactory(length: Int, object1: T, sf: BufferFactory): T {
        val checkLength = length != Int.MIN_VALUE
        this.object1 = object1

        // Test output to stream, large buffer.
        var outStream = ByteArrayOutputStream()
        output = sf.createOutput(outStream, 4096)
        kryo!!.writeClassAndObject(output, object1)
        output!!.flush()
        if (debug) println()

        // Test input from stream, large buffer.
        val out = outStream.toByteArray()
        input = sf.createInput(ByteArrayInputStream(out), 4096)
        object2 = kryo!!.readClassAndObject(input)
        doAssertEquals(object1, object2)
        if (checkLength) {
            Assert.assertEquals("Incorrect number of bytes read.", length.toLong(), input!!.total())
            Assert.assertEquals("Incorrect number of bytes written.", length.toLong(), output!!.total())
        }
        if (debug) return object2 as T

        // Test output to stream, small buffer.
        outStream = ByteArrayOutputStream()
        output = sf.createOutput(outStream, 10)
        kryo!!.writeClassAndObject(output, object1)
        output!!.flush()

        // Test input from stream, small buffer.
        input = sf.createInput(ByteArrayInputStream(outStream.toByteArray()), 10)
        object2 = kryo!!.readClassAndObject(input)
        doAssertEquals(object1, object2)
        if (checkLength) Assert.assertEquals("Incorrect number of bytes read.", length.toLong(), input!!.total())
        if (object1 != null) {
            // Test null with serializer.
            val serializer: Serializer<*> = kryo!!.getRegistration(object1.javaClass).serializer
            output!!.reset()
            outStream.reset()
            kryo!!.writeObjectOrNull(output, null, serializer)
            output!!.flush()

            // Test null from byte array with and without serializer.
            input = sf.createInput(ByteArrayInputStream(outStream.toByteArray()), 10)
            Assert.assertNull(kryo!!.readObjectOrNull(input, object1.javaClass, serializer))
            input = sf.createInput(ByteArrayInputStream(outStream.toByteArray()), 10)
            Assert.assertNull(kryo!!.readObjectOrNull(input, object1.javaClass))
        }

        // Test output to byte array.
        output = sf.createOutput(length * 2, -1)
        kryo!!.writeClassAndObject(output, object1)
        output!!.flush()

        // Test input from byte array.
        input = sf.createInput(output!!.toBytes())
        object2 = kryo!!.readClassAndObject(input)
        doAssertEquals(object1, object2)
        if (checkLength) {
            Assert.assertEquals("Incorrect length.", length.toLong(), output!!.total())
            Assert.assertEquals("Incorrect number of bytes read.", length.toLong(), input!!.total())
        }
        input!!.reset()
        if (supportsCopy) {
            // Test copy.
            var copy: T = kryo!!.copy(object1)
            doAssertEquals(object1, copy)
            copy = kryo!!.copyShallow(object1)
            doAssertEquals(object1, copy)
        }
        return object2 as T
    }

    protected fun doAssertEquals(object1: Any?, object2: Any?) {
        Assert.assertEquals(arrayToList(object1), arrayToList(object2))
    }

    companion object {
        // When true, roundTrip will only do a single write/read to make debugging easier (breaks some tests).
        private const val debug = false
        fun arrayToList(array: Any?): Any? {
            if (array == null || !array.javaClass.isArray) return array

            val list: ArrayList<Any?> = ArrayList(Array.getLength(array))
            var i = 0
            val n = Array.getLength(array)
            while (i < n) {
                val array1 = Array.get(array, i)
                val element = arrayToList(array1)
                list.add(element)
                i++
            }
            return list
        }

        fun list(vararg items: Any): ArrayList<*> {
            val list: ArrayList<Any> = ArrayList()
            for (item in items) list.add(item)
            return list
        }
    }
}
