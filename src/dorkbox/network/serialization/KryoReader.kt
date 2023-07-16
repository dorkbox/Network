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
import org.agrona.DirectBuffer

/**
 * READ and WRITE are exclusive to each other and can be performed in different threads.
 *
 * snappycomp   :       7.534 micros/op;  518.5 MB/s (output: 55.1%)
 * snappyuncomp :       1.391 micros/op; 2808.1 MB/s
 * lz4comp      :       6.210 micros/op;  629.0 MB/s (output: 55.4%)
 * lz4uncomp    :       0.641 micros/op; 6097.9 MB/s
 */
class KryoReader<CONNECTION: Connection>(private val maxMessageSize: Int) : Kryo() {
    companion object {
        internal const val DEBUG = false
    }

    // for kryo serialization
    private val readerBuffer = AeronInput()

    // This is unique per connection. volatile/etc is not necessary because it is set/read in the same thread
    lateinit var connection: CONNECTION


//    // crypto + compression have to work with native byte arrays, so here we go...
//    private val reader = Input(maxMessageSize)
//    private val temp = ByteArray(maxMessageSize)
//
//    private val cipher = Cipher.getInstance(CryptoManagement.AES_ALGORITHM)!!
//    private val decompressor = LZ4Factory.fastestInstance().fastDecompressor()!!

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
//    fun readCompressed(logger: KLogger, input: Input, length: Int): Any? {
//        ////////////////
//        // Note: we CANNOT write BACK to the buffer as "temp" storage, since there could be additional data on it!
//        ////////////////
//
//        // get the decompressed length (at the beginning of the array)
//        var length = length
//        val uncompressedLength = input.readInt(true)
//        if (uncompressedLength > (maxMessageSize*2)) {
//            throw IOException("Uncompressed size ($uncompressedLength) is larger than max allowed size (${maxMessageSize*2})!")
//        }
//
//        // because 1-4 bytes for the decompressed size (this number is never negative)
//        val lengthLength = OptimizeUtilsByteArray.intLength(uncompressedLength, true)
//        val start = input.position()
//
//        // have to adjust for uncompressed length-length
//        length -= lengthLength
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
//            logger.error(
//                OS.LINE_SEPARATOR +
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
//    fun readCompressed(logger: KLogger, connection: CONNECTION, input: Input, length: Int): Any? {
//        ////////////////
//        // Note: we CANNOT write BACK to the buffer as "temp" storage, since there could be additional data on it!
//        ////////////////
//
//        // get the decompressed length (at the beginning of the array)
//        var length = length
//        val uncompressedLength = input.readInt(true)
//        if (uncompressedLength > (maxMessageSize*2)) {
//            throw IOException("Uncompressed size ($uncompressedLength) is larger than max allowed size (${maxMessageSize*2})!")
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
//    fun readCrypto(logger: KLogger, connection: CONNECTION, buffer: Input, length: Int): Any? {
//        // read out the crypto IV
//        var length = length
//        val iv = ByteArray(CryptoManagement.GCM_IV_LENGTH_BYTES)
//        buffer.readBytes(iv, 0, CryptoManagement.GCM_IV_LENGTH_BYTES)
//
//        // have to adjust for the IV
//        length -= CryptoManagement.GCM_IV_LENGTH_BYTES
//
//        /////////// decrypt data
//        try {
//            cipher.init(Cipher.DECRYPT_MODE, connection.cryptoKey, GCMParameterSpec(CryptoManagement.GCM_TAG_LENGTH_BITS, iv))
//        } catch (e: Exception) {
//            throw IOException("Unable to AES decrypt the data", e)
//        }
//
//        // have to copy out bytes, we reuse the reader byte array!
//        buffer.readBytes(reader.buffer, 0, length)
//        if (DEBUG) {
//            val ivString = iv.toHexString()
//            val crypto = reader.buffer.toHexString(length = length)
//            logger.error("IV: (12)" + OS.LINE_SEPARATOR + ivString +
//                    OS.LINE_SEPARATOR + "CRYPTO: (" + length + ")" + OS.LINE_SEPARATOR + crypto)
//        }
//
//        val decryptedLength: Int
//        decryptedLength = try {
//            cipher.doFinal(reader.buffer, 0, length, temp, 0)
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
//            val compressed = temp.toHexString(start = start, length = endWithoutUncompressedLength)
//            val orig = reader.buffer.toHexString(length = uncompressedLength)
//            logger.error("COMPRESSED: (" + endWithoutUncompressedLength + ")" + OS.LINE_SEPARATOR + compressed +
//                    OS.LINE_SEPARATOR +
//                    "ORIG: (" + uncompressedLength + ")" + OS.LINE_SEPARATOR + orig)
//        }
//
//        // read the object from the buffer.
//        return read(connection, reader)
//    }
}
