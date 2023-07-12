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
import com.esotericsoftware.kryo.io.Input
import dorkbox.network.connection.Connection
import net.jpountz.lz4.LZ4Factory
import org.agrona.DirectBuffer

/**
 * READ and WRITE are exclusive to each other and can be performed in different threads.
 *
 */
class KryoReader<CONNECTION: Connection>(maxMessageSize: Int) : Kryo() {
    companion object {
        internal const val DEBUG = false

        internal val factory = LZ4Factory.fastestInstance()
    }

    // for kryo serialization
    private val readerBuffer = AeronInput()

    // crypto + compression have to work with native byte arrays, so here we go...
    private val reader = Input(maxMessageSize)

    // This is unique per connection. volatile/etc is not necessary because it is set/read in the same thread
    lateinit var connection: CONNECTION

//    private val secureRandom = SecureRandom()
//    private var cipher: Cipher? = null
    private val decompressor = factory.fastDecompressor()


    // The IV for AES-GCM must be 12 bytes, since it's 4 (salt) + 4 (external counter) + 4 (GCM counter)
    // The 12 bytes IV is created during connection registration, and during the AES-GCM crypto, we override the last 8 with this
    // counter, which is also transmitted as an optimized int. (which is why it starts at 0, so the transmitted bytes are small)
//    private val aes_gcm_iv = atomic(0)

//    /**
//     * This is the per-message sequence number.
//     *
//     * The IV for AES-GCM must be 12 bytes, since it's 4 (salt) + 4 (external counter) + 4 (GCM counter)
//     * The 12 bytes IV is created during connection registration, and during the AES-GCM crypto, we override the last 8 with this
//     * counter, which is also transmitted as an optimized int. (which is why it starts at 0, so the transmitted bytes are small)
//     */
//    fun nextGcmSequence(): Long {
//        return aes_gcm_iv.getAndIncrement()
//    }
//
//    /**
//     * @return the AES key. key=32 byte, iv=12 bytes (AES-GCM implementation).
//     */
//    fun cryptoKey(): SecretKey {
////        return channelWrapper.cryptoKey()
//    }


//
//    companion object {
//
//
//
//        // snappycomp   :       7.534 micros/op;  518.5 MB/s (output: 55.1%)
//        // snappyuncomp :       1.391 micros/op; 2808.1 MB/s
//        // lz4comp      :       6.210 micros/op;  629.0 MB/s (output: 55.4%)
//        // lz4uncomp    :       0.641 micros/op; 6097.9 MB/s
//        private val factory = LZ4Factory.fastestInstance()
//        private const val ALGORITHM = "AES/GCM/NoPadding"
//        private const val TAG_LENGTH_BIT = 128
//        private const val IV_LENGTH_BYTE = 12
//    }

//    init {
//        cipher = try {
//            Cipher.getInstance(ALGORITHM)
//        } catch (e: Exception) {
//            throw IllegalStateException("could not get cipher instance", e)
//        }
//    }

    /**
     * NOTE: THIS CANNOT BE USED FOR ANYTHING RELATED TO RMI!
     *
     * INPUT:
     * ++++++++++++++++++++++++++
     * + class and object bytes +
     * ++++++++++++++++++++++++++
     */
    @Throws(Exception::class)
    fun read(buffer: DirectBuffer): Any? {
        // this properly sets the buffer info
        readerBuffer.setBuffer(buffer, 0, buffer.capacity())
        return readClassAndObject(readerBuffer)
    }

    /**
     * NOTE: THIS CANNOT BE USED FOR ANYTHING RELATED TO RMI!
     *
     * INPUT:
     * ++++++++++++++++++++++++++
     * + class and object bytes +
     * ++++++++++++++++++++++++++
     */
    @Throws(Exception::class)
    fun read(buffer: DirectBuffer, offset: Int, length: Int): Any? {
        // this properly sets the buffer info
        readerBuffer.setBuffer(buffer, offset, length)
        return readClassAndObject(readerBuffer)
    }

    /**
     * INPUT:
     * ++++++++++++++++++++++++++
     * + class and object bytes +
     * ++++++++++++++++++++++++++
     */
    @Throws(Exception::class)
    fun read(buffer: DirectBuffer, offset: Int, length: Int, connection: CONNECTION): Any? {
        // required by RMI and some serializers to determine which connection wrote (or has info about) this object
        this.connection = connection

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
     * NOTE: THIS CANNOT BE USED FOR ANYTHING RELATED TO RMI!
     *
     * INPUT:
     * ++++++++++++++++++++++++++
     * + class and object bytes +
     * ++++++++++++++++++++++++++
     */
    fun read(reader: Input): Any? {
        return readClassAndObject(reader)
    }

    /**
     * INPUT:
     * ++++++++++++++++++++++++++
     * + class and object bytes +
     * ++++++++++++++++++++++++++
     */
    private fun read(connection: CONNECTION, reader: Input): Any? {
        // required by RMI and some serializers to determine which connection wrote (or has info about) this object
        this.connection = connection

        return readClassAndObject(reader)
    }


    /**
     * OUTPUT:
     * ++++++++++++++++
     * + object bytes +
     * ++++++++++++++++
     */
    fun readBytes(): ByteArray {
        val dataLength = readerBuffer.readVarInt(true)
        return readerBuffer.readBytes(dataLength)
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
//    fun readCompressed(logger: Logger, input: Input, length: Int): Any {
//        ////////////////
//        // Note: we CANNOT write BACK to the buffer as "temp" storage, since there could be additional data on it!
//        ////////////////
//
//        // get the decompressed length (at the beginning of the array)
//        var length = length
//        val uncompressedLength = input.readInt(true)
//        if (uncompressedLength > ABSOLUTE_MAX_SIZE_OBJECT) {
//            throw IOException("Uncompressed size ($uncompressedLength) is larger than max allowed size ($ABSOLUTE_MAX_SIZE_OBJECT)!")
//        }
//
//        // because 1-4 bytes for the decompressed size (this number is never negative)
//        val lengthLength = OptimizeUtilsByteArray.intLength(uncompressedLength, true)
//        val start = input.position()
//
//        // have to adjust for uncompressed length-length
//        length = length - lengthLength
//
//
//        ///////// decompress data
//        input.readBytes(temp, 0, length)
//
//
//        // LZ4 decompress, requires the size of the ORIGINAL length (because we use the FAST decompressor)
//        reader.reset()
//        decompressor.decompress(temp, 0, reader.buffer, 0, uncompressedLength)
//        reader.setLimit(uncompressedLength)
//
//        if (DEBUG) {
//            val compressed = Sys.bytesToHex(temp, start, length)
//            val orig = Sys.bytesToHex(reader.buffer, start, uncompressedLength)
//            logger.error(OS.LINE_SEPARATOR +
//                    "COMPRESSED: (" + length + ")" + OS.LINE_SEPARATOR + compressed +
//                    OS.LINE_SEPARATOR +
//                    "ORIG: (" + uncompressedLength + ")" + OS.LINE_SEPARATOR + orig)
//        }
//
//        // read the object from the buffer.
//        return read(reader)
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
//    fun readCompressed(logger: Logger, connection: Connection, input: Input, length: Int): Any {
//        ////////////////
//        // Note: we CANNOT write BACK to the buffer as "temp" storage, since there could be additional data on it!
//        ////////////////
//
//        // get the decompressed length (at the beginning of the array)
//        var length = length
//        val uncompressedLength = input.readInt(true)
//        if (uncompressedLength > ABSOLUTE_MAX_SIZE_OBJECT) {
//            throw IOException("Uncompressed size ($uncompressedLength) is larger than max allowed size ($ABSOLUTE_MAX_SIZE_OBJECT)!")
//        }
//
//        // because 1-4 bytes for the decompressed size (this number is never negative)
//        val lengthLength = OptimizeUtilsByteArray.intLength(uncompressedLength, true)
//        val start = input.position()
//
//        // have to adjust for uncompressed length-length
//        length = length - lengthLength
//
//
//        ///////// decompress data
//        input.readBytes(temp, 0, length)
//
//
//        // LZ4 decompress, requires the size of the ORIGINAL length (because we use the FAST decompressor)
//        reader.reset()
//        decompressor.decompress(temp, 0, reader.buffer, 0, uncompressedLength)
//        reader.setLimit(uncompressedLength)
//        if (DEBUG) {
//            val compressed = Sys.bytesToHex(input.readAllBytes(), start, length)
//            val orig = Sys.bytesToHex(reader.buffer, start, uncompressedLength)
//            logger.error(OS.LINE_SEPARATOR +
//                    "COMPRESSED: (" + length + ")" + OS.LINE_SEPARATOR + compressed +
//                    OS.LINE_SEPARATOR +
//                    "ORIG: (" + uncompressedLength + ")" + OS.LINE_SEPARATOR + orig)
//        }
//
//        // read the object from the buffer.
//        return read(connection, reader)
//    }
//


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
