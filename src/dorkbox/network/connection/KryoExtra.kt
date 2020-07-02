/*
 * Copyright 2010 dorkbox, llc
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
package dorkbox.network.connection

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import dorkbox.network.pipeline.AeronInput
import dorkbox.network.pipeline.AeronOutput
import dorkbox.network.rmi.ConnectionRmiSupport
import dorkbox.network.serialization.NetworkSerializationManager
import dorkbox.util.OS
import dorkbox.util.bytes.OptimizeUtilsByteArray
import dorkbox.util.bytes.OptimizeUtilsByteBuf
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import net.jpountz.lz4.LZ4Factory
import org.agrona.DirectBuffer
import org.slf4j.Logger
import java.io.IOException
import java.security.SecureRandom
import javax.crypto.Cipher

/**
 * Nothing in this class is thread safe
 */
class KryoExtra(val serializationManager: NetworkSerializationManager) : Kryo() {
    // for kryo serialization
    private val readerBuffer = AeronInput()
    val writerBuffer = AeronOutput()

    // crypto + compression have to work with native byte arrays, so here we go...
    private val reader = Input(ABSOLUTE_MAX_SIZE_OBJECT)
    private val writer = Output(ABSOLUTE_MAX_SIZE_OBJECT)
    private val temp = ByteArray(ABSOLUTE_MAX_SIZE_OBJECT)



    // volatile to provide object visibility for entire class. This is unique per connection
    lateinit var rmiSupport: ConnectionRmiSupport
    lateinit var connection: Connection_

    private val secureRandom = SecureRandom()
    private var cipher: Cipher? = null
    private val compressor = factory.fastCompressor()
    private val decompressor = factory.fastDecompressor()


    companion object {
        private const val ABSOLUTE_MAX_SIZE_OBJECT = 500000 // by default, this is about 500k
        private const val DEBUG = false

        // snappycomp   :       7.534 micros/op;  518.5 MB/s (output: 55.1%)
        // snappyuncomp :       1.391 micros/op; 2808.1 MB/s
        // lz4comp      :       6.210 micros/op;  629.0 MB/s (output: 55.4%)
        // lz4uncomp    :       0.641 micros/op; 6097.9 MB/s
        private val factory = LZ4Factory.fastestInstance()
        private const val ALGORITHM = "AES/GCM/NoPadding"
        private const val TAG_LENGTH_BIT = 128
        private const val IV_LENGTH_BYTE = 12
    }

    init {
        cipher = try {
            Cipher.getInstance(ALGORITHM)
        } catch (e: Exception) {
            throw IllegalStateException("could not get cipher instance", e)
        }
    }

    /**
     * OUTPUT:
     * ++++++++++++++++++++++++++
     * + class and object bytes +
     * ++++++++++++++++++++++++++
     */
    @Throws(Exception::class)
    fun write(connection: Connection_, message: Any) {
        // required by RMI and some serializers to determine which connection wrote (or has info about) this object
        this.connection = connection
        this.rmiSupport = connection.rmiSupport()

        writerBuffer.reset()
        writeClassAndObject(writerBuffer, message)
    }

    /**
     * INPUT:
     * ++++++++++++++++++++++++++
     * + class and object bytes +
     * ++++++++++++++++++++++++++
     */
    @Throws(Exception::class)
    fun read(buffer: DirectBuffer, offset: Int, length: Int, connection: Connection_): Any {
        // required by RMI and some serializers to determine which connection wrote (or has info about) this object
        this.connection = connection
        this.rmiSupport = connection.rmiSupport()

        // this properly sets the buffer info
        readerBuffer.setBuffer(buffer, offset, length)

        return readClassAndObject(readerBuffer)
    }


    ////////////////
    ////////////////
    ////////////////
    // for more complicated writes, sadly, we have to deal DIRECTLY with byte arrays
    ////////////////
    ////////////////
    ////////////////

    /**
     * OUTPUT:
     * ++++++++++++++++++++++++++
     * + class and object bytes +
     * ++++++++++++++++++++++++++
     */
    private fun write(connection: Connection_, writer: Output, message: Any) {
        // required by RMI and some serializers to determine which connection wrote (or has info about) this object
        this.connection = connection
        this.rmiSupport = connection.rmiSupport()

        // write the object to the NORMAL output buffer!
        writer.reset()
        writeClassAndObject(writer, message)
    }

    /**
     * INPUT:
     * ++++++++++++++++++++++++++
     * + class and object bytes +
     * ++++++++++++++++++++++++++
     */
    private fun read(connection: Connection_, reader: Input): Any {
        // required by RMI and some serializers to determine which connection wrote (or has info about) this object
        this.connection = connection
        this.rmiSupport = connection.rmiSupport()

        return readClassAndObject(reader)
    }

    /**
     * BUFFER:
     * ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
     * +  uncompressed length (1-4 bytes)  +  compressed data   +
     * ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
     *
     * COMPRESSED DATA:
     * ++++++++++++++++++++++++++
     * + class and object bytes +
     * ++++++++++++++++++++++++++
     */
    fun writeCompressed(logger: Logger, connection: Connection_, buffer: ByteBuf, message: Any) {
        // write the object to a TEMP buffer! this will be compressed later
        write(connection, writer, message)

        // save off how much data the object took
        val length = writer.position()
        val maxCompressedLength = compressor.maxCompressedLength(length)

        ////////// compressing data
        // we ALWAYS compress our data stream -- because of how AES-GCM pads data out, the small input (that would result in a larger
        // output), will be negated by the increase in size by the encryption
        val compressOutput = temp

        // LZ4 compress.
        val compressedLength = compressor.compress(writer.buffer, 0, length, compressOutput, 0, maxCompressedLength)
        if (DEBUG) {
            val orig = ByteBufUtil.hexDump(writer.buffer, 0, length)
            val compressed = ByteBufUtil.hexDump(compressOutput, 0, compressedLength)
            logger.error(OS.LINE_SEPARATOR +
                    "ORIG: (" + length + ")" + OS.LINE_SEPARATOR + orig +
                    OS.LINE_SEPARATOR +
                    "COMPRESSED: (" + compressedLength + ")" + OS.LINE_SEPARATOR + compressed)
        }

        // now write the ORIGINAL (uncompressed) length. This is so we can use the FAST decompress version
        OptimizeUtilsByteBuf.writeInt(buffer, length, true)

        // have to copy over the orig data, because we used the temp buffer. Also have to account for the length of the uncompressed size
        buffer.writeBytes(compressOutput, 0, compressedLength)
    }

    /**
     * BUFFER:
     * ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
     * +  uncompressed length (1-4 bytes)  +  compressed data   +
     * ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
     *
     * COMPRESSED DATA:
     * ++++++++++++++++++++++++++
     * + class and object bytes +
     * ++++++++++++++++++++++++++
     */
    fun readCompressed(logger: Logger, connection: Connection_, buffer: ByteBuf, length: Int): Any {
        ////////////////
        // Note: we CANNOT write BACK to the buffer as "temp" storage, since there could be additional data on it!
        ////////////////

        // get the decompressed length (at the beginning of the array)
        var length = length
        val uncompressedLength = OptimizeUtilsByteBuf.readInt(buffer, true)
        if (uncompressedLength > ABSOLUTE_MAX_SIZE_OBJECT) {
            throw IOException("Uncompressed size ($uncompressedLength) is larger than max allowed size ($ABSOLUTE_MAX_SIZE_OBJECT)!")
        }

        // because 1-4 bytes for the decompressed size (this number is never negative)
        val lengthLength = OptimizeUtilsByteArray.intLength(uncompressedLength, true)
        val start = buffer.readerIndex()

        // have to adjust for uncompressed length-length
        length = length - lengthLength


        ///////// decompress data
        buffer.readBytes(temp, 0, length)


        // LZ4 decompress, requires the size of the ORIGINAL length (because we use the FAST decompressor)
        reader.reset()
        decompressor.decompress(temp, 0, reader.buffer, 0, uncompressedLength)
        reader.setLimit(uncompressedLength)
        if (DEBUG) {
            val compressed = ByteBufUtil.hexDump(buffer, start, length)
            val orig = ByteBufUtil.hexDump(reader.buffer, start, uncompressedLength)
            logger.error(OS.LINE_SEPARATOR +
                    "COMPRESSED: (" + length + ")" + OS.LINE_SEPARATOR + compressed +
                    OS.LINE_SEPARATOR +
                    "ORIG: (" + uncompressedLength + ")" + OS.LINE_SEPARATOR + orig)
        }

        // read the object from the buffer.
        return read(connection, reader)
    }

    /**
     * BUFFER:
     * +++++++++++++++++++++++++++++++
     * +  IV (12)  + encrypted data  +
     * +++++++++++++++++++++++++++++++
     *
     * ENCRYPTED DATA:
     * ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
     * +  uncompressed length (1-4 bytes)  +  compressed data   +
     * ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
     *
     * COMPRESSED DATA:
     * ++++++++++++++++++++++++++
     * + class and object bytes +
     * ++++++++++++++++++++++++++
     */
//    fun writeCrypto(logger: Logger, connection: Connection_, buffer: ByteBuf, message: Any) {
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
//            val orig = ByteBufUtil.hexDump(writer.buffer, 0, length)
//            val compressed = ByteBufUtil.hexDump(compressOutput, 4, compressedLength)
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
//        val cryptoKey = connection.cryptoKey()
//        val iv = ByteArray(IV_LENGTH_BYTE) // NEVER REUSE THIS IV WITH SAME KEY
//        secureRandom.nextBytes(iv)
//        val parameterSpec = GCMParameterSpec(TAG_LENGTH_BIT, iv) // 128 bit auth tag length
//        try {
//            cipher!!.init(Cipher.ENCRYPT_MODE, cryptoKey, parameterSpec)
//        } catch (e: Exception) {
//            throw IOException("Unable to AES encrypt the data", e)
//        }
//
//        // we REUSE the writer buffer! (since that data is now compressed in a different array)
//        val encryptedLength: Int
//        encryptedLength = try {
//            cipher!!.doFinal(compressOutput, start, compressedArrayLength, writer.buffer, 0)
//        } catch (e: Exception) {
//            throw IOException("Unable to AES encrypt the data", e)
//        }
//
//        // write out our IV
//        buffer.writeBytes(iv, 0, IV_LENGTH_BYTE)
//        Arrays.fill(iv, 0.toByte()) // overwrite the IV with zeros so we can't leak this value
//
//        // have to copy over the orig data, because we used the temp buffer
//        buffer.writeBytes(writer.buffer, 0, encryptedLength)
//        if (DEBUG) {
//            val ivString = ByteBufUtil.hexDump(iv, 0, IV_LENGTH_BYTE)
//            val crypto = ByteBufUtil.hexDump(writer.buffer, 0, encryptedLength)
//            logger.error(OS.LINE_SEPARATOR +
//                    "IV: (12)" + OS.LINE_SEPARATOR + ivString +
//                    OS.LINE_SEPARATOR +
//                    "CRYPTO: (" + encryptedLength + ")" + OS.LINE_SEPARATOR + crypto)
//        }
//    }

    /**
     * BUFFER:
     * +++++++++++++++++++++++++++++++
     * +  IV (12)  + encrypted data  +
     * +++++++++++++++++++++++++++++++
     *
     * ENCRYPTED DATA:
     * ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
     * +  uncompressed length (1-4 bytes)  +  compressed data   +
     * ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
     *
     * COMPRESSED DATA:
     * ++++++++++++++++++++++++++
     * + class and object bytes +
     * ++++++++++++++++++++++++++
     */
//    fun readCrypto(logger: Logger, connection: Connection_, buffer: ByteBuf, length: Int): Any {
//        // read out the crypto IV
//        var length = length
//        val iv = ByteArray(IV_LENGTH_BYTE)
//        buffer.readBytes(iv, 0, IV_LENGTH_BYTE)
//
//        // have to adjust for the IV
//        length = length - IV_LENGTH_BYTE
//
//        /////////// decrypt data
//        val cryptoKey = connection.cryptoKey()
//        try {
//            cipher!!.init(Cipher.DECRYPT_MODE, cryptoKey, GCMParameterSpec(TAG_LENGTH_BIT, iv))
//        } catch (e: Exception) {
//            throw IOException("Unable to AES decrypt the data", e)
//        }
//
//        // have to copy out bytes, we reuse the reader byte array!
//        buffer.readBytes(reader.buffer, 0, length)
//        if (DEBUG) {
//            val ivString = ByteBufUtil.hexDump(iv, 0, IV_LENGTH_BYTE)
//            val crypto = ByteBufUtil.hexDump(reader.buffer, 0, length)
//            logger.error("IV: (12)" + OS.LINE_SEPARATOR + ivString +
//                    OS.LINE_SEPARATOR + "CRYPTO: (" + length + ")" + OS.LINE_SEPARATOR + crypto)
//        }
//        val decryptedLength: Int
//        decryptedLength = try {
//            cipher!!.doFinal(reader.buffer, 0, length, temp, 0)
//        } catch (e: Exception) {
//            throw IOException("Unable to AES decrypt the data", e)
//        }
//
//        ///////// decompress data -- as it's ALWAYS compressed
//
//        // get the decompressed length (at the beginning of the array)
//        val uncompressedLength = OptimizeUtilsByteArray.readInt(temp, true)
//
//        // where does our data start, AFTER the length field
//        val start = OptimizeUtilsByteArray.intLength(uncompressedLength, true) // because 1-4 bytes for the uncompressed size;
//
//        // LZ4 decompress, requires the size of the ORIGINAL length (because we use the FAST decompressor
//        reader.reset()
//        decompressor.decompress(temp, start, reader.buffer, 0, uncompressedLength)
//        reader.setLimit(uncompressedLength)
//        if (DEBUG) {
//            val endWithoutUncompressedLength = decryptedLength - start
//            val compressed = ByteBufUtil.hexDump(temp, start, endWithoutUncompressedLength)
//            val orig = ByteBufUtil.hexDump(reader.buffer, 0, uncompressedLength)
//            logger.error("COMPRESSED: (" + endWithoutUncompressedLength + ")" + OS.LINE_SEPARATOR + compressed +
//                    OS.LINE_SEPARATOR +
//                    "ORIG: (" + uncompressedLength + ")" + OS.LINE_SEPARATOR + orig)
//        }
//
//        // read the object from the buffer.
//        return read(connection, reader)
//    }
}
