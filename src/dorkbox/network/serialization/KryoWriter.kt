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
package dorkbox.network.serialization

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.io.Output
import dorkbox.network.connection.Connection
import io.aeron.logbuffer.BufferClaim
import java.util.*

/**
 * READ and WRITE are exclusive to each other and can be performed in different threads.
 *
 * snappycomp   :       7.534 micros/op;  518.5 MB/s (output: 55.1%)
 * snappyuncomp :       1.391 micros/op; 2808.1 MB/s
 * lz4comp      :       6.210 micros/op;  629.0 MB/s (output: 55.4%)
 * lz4uncomp    :       0.641 micros/op; 6097.9 MB/s
 *
 */
open class KryoWriter<CONNECTION: Connection>(maxMessageSize: Int) : Kryo() {
    companion object {
        internal const val DEBUG = false
    }

    // for claiming a portion of the termBuffer in Aeron to write data to
    internal val bufferClaim = BufferClaim()

    // for kryo serialization
    internal val writerBuffer = AeronOutput(maxMessageSize)

    // This is unique per connection. volatile/etc is not necessary because it is set/read in the same thread
    lateinit var connection: CONNECTION

//    // crypto + compression have to work with native byte arrays, so here we go...
//    private val writer = Output(maxMessageSize)
//    private val temp = ByteArray(maxMessageSize)
//
//    private val cipher = Cipher.getInstance(CryptoManagement.AES_ALGORITHM)!!
//    private val compressor = LZ4Factory.nativeInstance().fastCompressor()


    /**
     * NOTE: THIS CANNOT BE USED FOR ANYTHING RELATED TO RMI!
     *
     * OUTPUT:
     * ++++++++++++++++++++++++++
     * + class and object bytes +
     * ++++++++++++++++++++++++++
     */
    @Throws(Exception::class)
    fun write(message: Any): AeronOutput {
        writerBuffer.reset()
        writeClassAndObject(writerBuffer, message)
        return writerBuffer
    }

    /**
     * OUTPUT:
     * ++++++++++++++++++++++++++
     * + class and object bytes +
     * ++++++++++++++++++++++++++
     */
    @Throws(Exception::class)
    fun write(connection: CONNECTION, message: Any): AeronOutput {
        // required by RMI and some serializers to determine which connection wrote (or has info about) this object
        this.connection = connection

        writerBuffer.reset()
        writeClassAndObject(writerBuffer, message)
        return writerBuffer
    }

    ////////////////
    ////////////////
    ////////////////
    // for more complicated writes, sadly, we have to deal DIRECTLY with byte arrays
    ////////////////
    ////////////////
    ////////////////

    /**
     * NOTE: THIS CANNOT BE USED FOR ANYTHING RELATED TO RMI!
     *
     * OUTPUT:
     * ++++++++++++++++++++++++++
     * + class and object bytes +
     * ++++++++++++++++++++++++++
     */
    fun write(writer: Output, message: Any) {
        // write the object to the NORMAL output buffer!
        writer.reset()
        writeClassAndObject(writer, message)
    }

    /**
     * OUTPUT:
     * ++++++++++++++++++++++++++
     * + class and object bytes +
     * ++++++++++++++++++++++++++
     */
    private fun write(connection: CONNECTION, writer: Output, message: Any) {
        // required by RMI and some serializers to determine which connection wrote (or has info about) this object
        this.connection = connection

        // write the object to the NORMAL output buffer!
        writer.reset()
        writeClassAndObject(writer, message)
    }

//    /**
//     * NOTE: THIS CANNOT BE USED FOR ANYTHING RELATED TO RMI!
//     *
//     * BUFFER:
//     * ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
//     * +  uncompressed length (1-4 bytes)  +  compressed data   +
//     * ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
//     *
//     * COMPRESSED DATA:
//     * ++++++++++++++++++++++++++
//     * + class and object bytes +
//     * ++++++++++++++++++++++++++
//     */
//    fun writeCompressed(logger: KLogger, output: Output, message: Any) {
//        // write the object to a TEMP buffer! this will be compressed later
//        write(writer, message)
//
//        // save off how much data the object took
//        val length = writer.position()
//        val maxCompressedLength = compressor.maxCompressedLength(length)
//
//        ////////// compressing data
//        // we ALWAYS compress our data stream -- because of how AES-GCM pads data out, the small input (that would result in a larger
//        // output), will be negated by the increase in size by the encryption
//        val compressOutput = temp
//
//
//        // LZ4 compress.
//        val compressedLength = compressor.compress(writer.buffer, 0, length, compressOutput, 0, maxCompressedLength)
//
//        if (DEBUG) {
//            val orig = writer.buffer.toHexString()
//            val compressed = compressOutput.toHexString()
//            logger.error(
//                OS.LINE_SEPARATOR +
//                         "ORIG: (" + length + ")" + OS.LINE_SEPARATOR + orig +
//                         OS.LINE_SEPARATOR +
//                         "COMPRESSED: (" + compressedLength + ")" + OS.LINE_SEPARATOR + compressed)
//        }
//
//        // now write the ORIGINAL (uncompressed) length. This is so we can use the FAST decompress version
//        output.writeInt(length, true)
//
//        // have to copy over the orig data, because we used the temp buffer. Also have to account for the length of the uncompressed size
//        output.writeBytes(compressOutput, 0, compressedLength)
//    }
//
//    /**
//     * BUFFER:
//     * ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
//     * +  uncompressed length (1-4 bytes)  +  compressed data   +
//     * ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
//     *
//     * COMPRESSED DATA:
//     * ++++++++++++++++++++++++++
//     * + class and object bytes +
//     * ++++++++++++++++++++++++++
//     */
//    fun writeCompressed(logger: KLogger, connection: CONNECTION, output: Output, message: Any) {
//        // write the object to a TEMP buffer! this will be compressed later
//        write(connection, writer, message)
//
//        // save off how much data the object took
//        val length = writer.position()
//        val maxCompressedLength = compressor.maxCompressedLength(length)
//
//        ////////// compressing data
//        // we ALWAYS compress our data stream -- because of how AES-GCM pads data out, the small input (that would result in a larger
//        // output), will be negated by the increase in size by the encryption
//        val compressOutput = temp
//
//        // LZ4 compress.
//        val compressedLength = compressor.compress(writer.buffer, 0, length, compressOutput, 0, maxCompressedLength)
//
//        if (DEBUG) {
//            val orig = writer.buffer.toHexString(length = length)
//            val compressed = compressOutput.toHexString(length = compressedLength)
//            logger.error("${OS.LINE_SEPARATOR}" +
//                                 "ORIG: ($length) ${OS.LINE_SEPARATOR}" +
//                                 "$orig ${OS.LINE_SEPARATOR}" +
//                                 "COMPRESSED: ($compressedLength) ${OS.LINE_SEPARATOR} " +
//                                 "$compressed")
//        }
//
//        // now write the ORIGINAL (uncompressed) length. This is so we can use the FAST decompress version
//        output.writeInt(length, true)
//
//        // have to copy over the orig data, because we used the temp buffer. Also have to account for the length of the uncompressed size
//        output.writeBytes(compressOutput, 0, compressedLength)
//    }
//
//
//
//    /**
//     * BUFFER:
//     * +++++++++++++++++++++++++++++++
//     * +  IV (12)  + encrypted data  +
//     * +++++++++++++++++++++++++++++++
//     *
//     * ENCRYPTED DATA:
//     * ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
//     * +  uncompressed length (1-4 bytes)  +  compressed data   +
//     * ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
//     *
//     * COMPRESSED DATA:
//     * ++++++++++++++++++++++++++
//     * + class and object bytes +
//     * ++++++++++++++++++++++++++
//     */
//    fun writeCrypto(logger: KLogger, connection: CONNECTION, buffer: Output, message: Any) {
//        // write the object to a TEMP buffer! this will be compressed later
//        write(connection, writer, message)
//
//        // save off how much data the object took
//        val length = writer.position()
//        val maxCompressedLength = compressor.maxCompressedLength(length)
//
//        ////////// compressing data
//        // we ALWAYS compress our data stream -- because of how AES-GCM pads data out, the small input (that would result in a larger
//        // output), will be negated by the increase in size by the encryption
//        val compressOutput = temp
//
//        // LZ4 compress. Offset by 4 in the dest array so we have room for the length
//        val compressedLength = compressor.compress(writer.buffer, 0, length, compressOutput, 4, maxCompressedLength)
//        if (DEBUG) {
//            val orig = writer.buffer.toHexString(length = length)
//            val compressed = compressOutput.toHexString(start = 4, length = compressedLength)
//            logger.error(OS.LINE_SEPARATOR +
//                    "ORIG: (" + length + ")" + OS.LINE_SEPARATOR + orig +
//                    OS.LINE_SEPARATOR +
//                    "COMPRESSED: (" + compressedLength + ")" + OS.LINE_SEPARATOR + compressed)
//        }
//
//        // now write the ORIGINAL (uncompressed) length. This is so we can use the FAST decompress version
//        val lengthLength = OptimizeUtilsByteArray.intLength(length, true)
//
//        // this is where we start writing the length data, so that the end of this lines up with the compressed data
//        val start = 4 - lengthLength
//        OptimizeUtilsByteArray.writeInt(compressOutput, length, true, start)
//
//        // now compressOutput contains "uncompressed length + data"
//        val compressedArrayLength = lengthLength + compressedLength
//
//
//        /////// encrypting data.
//        val iv = ByteArray(CryptoManagement.GCM_IV_LENGTH_BYTES) // NEVER REUSE THIS IV WITH SAME KEY
//        CryptoManagement.secureRandom.nextBytes(iv)
//
//        val parameterSpec = GCMParameterSpec(CryptoManagement.GCM_TAG_LENGTH_BITS, iv) // 128 bit auth tag length
//
//        try {
//            cipher.init(Cipher.ENCRYPT_MODE, connection.cryptoKey, parameterSpec)
//        } catch (e: Exception) {
//            throw IOException("Unable to AES encrypt the data", e)
//        }
//
//        // we REUSE the writer buffer! (since that data is now compressed in a different array)
//        val encryptedLength: Int
//        encryptedLength = try {
//            cipher.doFinal(compressOutput, start, compressedArrayLength, writer.buffer, 0)
//        } catch (e: Exception) {
//            throw IOException("Unable to AES encrypt the data", e)
//        }
//
//        // write out our IV
//        buffer.writeBytes(iv, 0, CryptoManagement.GCM_IV_LENGTH_BYTES)
//        Arrays.fill(iv, 0.toByte()) // overwrite the IV with zeros so we can't leak this value
//
//        // have to copy over the orig data, because we used the temp buffer
//        buffer.writeBytes(writer.buffer, 0, encryptedLength)
//
//        if (DEBUG) {
//            val ivString = iv.toHexString()
//            val crypto = writer.buffer.toHexString(length = encryptedLength)
//            logger.error(OS.LINE_SEPARATOR +
//                    "IV: (12)" + OS.LINE_SEPARATOR + ivString +
//                    OS.LINE_SEPARATOR +
//                    "CRYPTO: (" + encryptedLength + ")" + OS.LINE_SEPARATOR + crypto)
//        }
//    }
}
