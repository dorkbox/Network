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
package dorkbox.network.connection;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import org.slf4j.Logger;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

import dorkbox.network.pipeline.ByteBufInput;
import dorkbox.network.pipeline.ByteBufOutput;
import dorkbox.network.rmi.ConnectionRmiSupport;
import dorkbox.network.rmi.RmiNopConnection;
import dorkbox.network.serialization.NetworkSerializationManager;
import dorkbox.util.OS;
import dorkbox.util.bytes.OptimizeUtilsByteArray;
import dorkbox.util.bytes.OptimizeUtilsByteBuf;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;

/**
 * Nothing in this class is thread safe
 */
@SuppressWarnings("Duplicates")
public
class KryoExtra extends Kryo {
    private static final int ABSOLUTE_MAX_SIZE_OBJECT = 500_000; // by default, this is about 500k
    private static final boolean DEBUG = false;

    private static final Connection_ NOP_CONNECTION = new RmiNopConnection();

    // snappycomp   :       7.534 micros/op;  518.5 MB/s (output: 55.1%)
    // snappyuncomp :       1.391 micros/op; 2808.1 MB/s
    // lz4comp      :       6.210 micros/op;  629.0 MB/s (output: 55.4%)
    // lz4uncomp    :       0.641 micros/op; 6097.9 MB/s
    private static final LZ4Factory factory = LZ4Factory.fastestInstance();

    // for kryo serialization
    private final ByteBufInput readerBuff = new ByteBufInput();
    private final ByteBufOutput writerBuff = new ByteBufOutput();

    // crypto + compression have to work with native byte arrays, so here we go...
    private final Input reader = new Input(ABSOLUTE_MAX_SIZE_OBJECT);
    private final Output writer = new Output(ABSOLUTE_MAX_SIZE_OBJECT);

    private final byte[] temp = new byte[ABSOLUTE_MAX_SIZE_OBJECT];


    // volatile to provide object visibility for entire class. This is unique per connection
    public volatile ConnectionRmiSupport rmiSupport;

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int TAG_LENGTH_BIT = 128;
    private static final int IV_LENGTH_BYTE = 12;

    private final SecureRandom secureRandom = new SecureRandom();
    private final Cipher cipher;

    private LZ4Compressor compressor = factory.fastCompressor();
    private LZ4FastDecompressor decompressor = factory.fastDecompressor();




    private NetworkSerializationManager serializationManager;

    public
    KryoExtra(final NetworkSerializationManager serializationManager) {
        super();

        this.serializationManager = serializationManager;

        try {
            cipher = Cipher.getInstance(ALGORITHM);
        } catch (Exception e) {
            throw new IllegalStateException("could not get cipher instance", e);
        }
    }

    /**
     * This is NOT ENCRYPTED
     */
    public synchronized
    void write(final ByteBuf buffer, final Object message) throws IOException {
        write(NOP_CONNECTION, buffer, message);
    }

    /**
     * This is NOT ENCRYPTED
     */
    public synchronized
    void write(final Connection_ connection, final ByteBuf buffer, final Object message) throws IOException {
        // required by RMI and some serializers to determine which connection wrote (or has info about) this object
        this.rmiSupport = connection.rmiSupport();

        // write the object to the NORMAL output buffer!
        writerBuff.setBuffer(buffer);

        writeClassAndObject(writerBuff, message);
    }

    /**
     *  ++++++++++++++++++++++++++
     *  + class and object bytes +
     *  ++++++++++++++++++++++++++
     */
    public synchronized
    Object read(final ByteBuf buffer) throws IOException {
        return read(NOP_CONNECTION, buffer);
    }

    /**
     *  ++++++++++++++++++++++++++
     *  + class and object bytes +
     *  ++++++++++++++++++++++++++
     */
    public synchronized
    Object read(final Connection_ connection, final ByteBuf buffer) throws IOException {
        // required by RMI and some serializers to determine which connection wrote (or has info about) this object
        this.rmiSupport = connection.rmiSupport();

        // read the object from the buffer.
        readerBuff.setBuffer(buffer);

        // this properly sets the readerIndex, but only if it's the correct buffer
        return readClassAndObject(readerBuff);
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
     *  ++++++++++++++++++++++++++
     *  + class and object bytes +
     *  ++++++++++++++++++++++++++
     */
    private
    void write(final Connection_ connection, final Output writer, final Object message) {
        // required by RMI and some serializers to determine which connection wrote (or has info about) this object
        this.rmiSupport = connection.rmiSupport();

        // write the object to the NORMAL output buffer!
        writer.reset();

        writeClassAndObject(writer, message);
    }

    /**
     * INPUT:
     *  ++++++++++++++++++++++++++
     *  + class and object bytes +
     *  ++++++++++++++++++++++++++
     */
    private
    Object read(final Connection_ connection, final Input reader) {
        // required by RMI and some serializers to determine which connection wrote (or has info about) this object
        this.rmiSupport = connection.rmiSupport();

        return readClassAndObject(reader);
    }


    public synchronized
    void writeCompressed(final Logger logger, final ByteBuf buffer, final Object message) throws IOException {
        writeCompressed(logger, NOP_CONNECTION, buffer, message);
    }

    /**
     * BUFFER:
     *  ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
     *  +  uncompressed length (1-4 bytes)  +  compressed data   +
     *  ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
     *
     * COMPRESSED DATA:
     *  ++++++++++++++++++++++++++
     *  + class and object bytes +
     *  ++++++++++++++++++++++++++
     */
    public synchronized
    void writeCompressed(final Logger logger, final Connection_ connection, final ByteBuf buffer, final Object message) throws IOException {
        // write the object to a TEMP buffer! this will be compressed later
        write(connection, writer, message);

        // save off how much data the object took
        int length = writer.position();
        int maxCompressedLength = compressor.maxCompressedLength(length);

        ////////// compressing data
        // we ALWAYS compress our data stream -- because of how AES-GCM pads data out, the small input (that would result in a larger
        // output), will be negated by the increase in size by the encryption
        byte[] compressOutput = temp;

        // LZ4 compress.
        int compressedLength = compressor.compress(writer.getBuffer(), 0, length, compressOutput, 0, maxCompressedLength);

        if (DEBUG) {
            String orig = ByteBufUtil.hexDump(writer.getBuffer(), 0, length);
            String compressed = ByteBufUtil.hexDump(compressOutput, 0, compressedLength);
            logger.error(OS.LINE_SEPARATOR +
                         "ORIG: (" + length + ")" + OS.LINE_SEPARATOR + orig +
                         OS.LINE_SEPARATOR +
                         "COMPRESSED: (" + compressedLength + ")" + OS.LINE_SEPARATOR + compressed);
        }

        // now write the ORIGINAL (uncompressed) length. This is so we can use the FAST decompress version
        OptimizeUtilsByteBuf.writeInt(buffer, length, true);

        // have to copy over the orig data, because we used the temp buffer. Also have to account for the length of the uncompressed size
        buffer.writeBytes(compressOutput, 0, compressedLength);
    }

    public synchronized
    Object readCompressed(final Logger logger, final ByteBuf buffer, int length) throws IOException {
        return readCompressed(logger, NOP_CONNECTION, buffer, length);
    }

    /**
     * BUFFER:
     *  ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
     *  +  uncompressed length (1-4 bytes)  +  compressed data   +
     *  ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
     *
     * COMPRESSED DATA:
     *  ++++++++++++++++++++++++++
     *  + class and object bytes +
     *  ++++++++++++++++++++++++++
     */
    public synchronized
    Object readCompressed(final Logger logger, final Connection_ connection, final ByteBuf buffer, int length) throws IOException {
        ////////////////
        // Note: we CANNOT write BACK to the buffer as "temp" storage, since there could be additional data on it!
        ////////////////

        // get the decompressed length (at the beginning of the array)
        final int uncompressedLength = OptimizeUtilsByteBuf.readInt(buffer, true);
        if (uncompressedLength > ABSOLUTE_MAX_SIZE_OBJECT) {
            throw new IOException("Uncompressed size (" + uncompressedLength + ") is larger than max allowed size (" + ABSOLUTE_MAX_SIZE_OBJECT + ")!");
        }

        // because 1-4 bytes for the decompressed size (this number is never negative)
        final int lengthLength = OptimizeUtilsByteArray.intLength(uncompressedLength, true);

        int start = buffer.readerIndex();

        // have to adjust for uncompressed length-length
        length = length - lengthLength;


        ///////// decompress data
        buffer.readBytes(temp, 0, length);


        // LZ4 decompress, requires the size of the ORIGINAL length (because we use the FAST decompressor)
        reader.reset();
        decompressor.decompress(temp, 0, reader.getBuffer(), 0, uncompressedLength);
        reader.setLimit(uncompressedLength);

        if (DEBUG) {
            String compressed = ByteBufUtil.hexDump(buffer, start, length);
            String orig = ByteBufUtil.hexDump(reader.getBuffer(), start, uncompressedLength);
            logger.error(OS.LINE_SEPARATOR +
                         "COMPRESSED: (" + length + ")" + OS.LINE_SEPARATOR + compressed +
                         OS.LINE_SEPARATOR +
                         "ORIG: (" + uncompressedLength + ")" + OS.LINE_SEPARATOR + orig);
        }

        // read the object from the buffer.
        return read(connection, reader);
    }

    /**
     * BUFFER:
     *  +++++++++++++++++++++++++++++++
     *  +  IV (12)  + encrypted data  +
     *  +++++++++++++++++++++++++++++++
     *
     * ENCRYPTED DATA:
     *  ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
     *  +  uncompressed length (1-4 bytes)  +  compressed data   +
     *  ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
     *
     * COMPRESSED DATA:
     *  ++++++++++++++++++++++++++
     *  + class and object bytes +
     *  ++++++++++++++++++++++++++
     */
    public synchronized
    void writeCrypto(final Logger logger, final Connection_ connection, final ByteBuf buffer, final Object message) throws IOException {
        // write the object to a TEMP buffer! this will be compressed later
        write(connection, writer, message);

        // save off how much data the object took
        int length = writer.position();
        int maxCompressedLength = compressor.maxCompressedLength(length);

        ////////// compressing data
        // we ALWAYS compress our data stream -- because of how AES-GCM pads data out, the small input (that would result in a larger
        // output), will be negated by the increase in size by the encryption
        byte[] compressOutput = temp;

        // LZ4 compress. Offset by 4 in the dest array so we have room for the length
        int compressedLength = compressor.compress(writer.getBuffer(), 0, length, compressOutput, 4, maxCompressedLength);

        if (DEBUG) {
            String orig = ByteBufUtil.hexDump(writer.getBuffer(), 0, length);
            String compressed = ByteBufUtil.hexDump(compressOutput, 4, compressedLength);
            logger.error(OS.LINE_SEPARATOR +
                         "ORIG: (" + length + ")" + OS.LINE_SEPARATOR + orig +
                         OS.LINE_SEPARATOR +
                         "COMPRESSED: (" + compressedLength + ")" + OS.LINE_SEPARATOR + compressed);
        }

        // now write the ORIGINAL (uncompressed) length. This is so we can use the FAST decompress version
        final int lengthLength = OptimizeUtilsByteArray.intLength(length, true);

        // this is where we start writing the length data, so that the end of this lines up with the compressed data
        int start = 4 - lengthLength;

        OptimizeUtilsByteArray.writeInt(compressOutput, length, true, start);

        // now compressOutput contains "uncompressed length + data"
        int compressedArrayLength = lengthLength + compressedLength;


        /////// encrypting data.
        final SecretKey cryptoKey = connection.cryptoKey();

        byte[] iv = new byte[IV_LENGTH_BYTE]; // NEVER REUSE THIS IV WITH SAME KEY
        secureRandom.nextBytes(iv);

        GCMParameterSpec parameterSpec = new GCMParameterSpec(TAG_LENGTH_BIT, iv); // 128 bit auth tag length
        try {
            cipher.init(Cipher.ENCRYPT_MODE, cryptoKey, parameterSpec);
        } catch (Exception e) {
            throw new IOException("Unable to AES encrypt the data", e);
        }

        // we REUSE the writer buffer! (since that data is now compressed in a different array)

        int encryptedLength;
        try {
            encryptedLength = cipher.doFinal(compressOutput, start, compressedArrayLength, writer.getBuffer(), 0);
        } catch (Exception e) {
            throw new IOException("Unable to AES encrypt the data", e);
        }

        // write out our IV
        buffer.writeBytes(iv, 0, IV_LENGTH_BYTE);

        Arrays.fill(iv, (byte) 0); // overwrite the IV with zeros so we can't leak this value

        // have to copy over the orig data, because we used the temp buffer
        buffer.writeBytes(writer.getBuffer(), 0, encryptedLength);

        if (DEBUG) {
            String ivString = ByteBufUtil.hexDump(iv, 0, IV_LENGTH_BYTE);
            String crypto = ByteBufUtil.hexDump(writer.getBuffer(), 0, encryptedLength);
            logger.error(OS.LINE_SEPARATOR +
                         "IV: (12)" + OS.LINE_SEPARATOR + ivString +
                         OS.LINE_SEPARATOR +
                         "CRYPTO: (" + encryptedLength + ")" + OS.LINE_SEPARATOR + crypto);
        }
    }


    /**
     * BUFFER:
     *  +++++++++++++++++++++++++++++++
     *  +  IV (12)  + encrypted data  +
     *  +++++++++++++++++++++++++++++++
     *
     * ENCRYPTED DATA:
     *  ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
     *  +  uncompressed length (1-4 bytes)  +  compressed data   +
     *  ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
     *
     * COMPRESSED DATA:
     *  ++++++++++++++++++++++++++
     *  + class and object bytes +
     *  ++++++++++++++++++++++++++
     */
    public
    Object readCrypto(final Logger logger, final Connection_ connection, final ByteBuf buffer, int length) throws IOException {
        // read out the crypto IV
        final byte[] iv =  new byte[IV_LENGTH_BYTE];
        buffer.readBytes(iv, 0 , IV_LENGTH_BYTE);

        // have to adjust for the IV
        length = length - IV_LENGTH_BYTE;

        /////////// decrypt data
        final SecretKey cryptoKey = connection.cryptoKey();

        try {
            cipher.init(Cipher.DECRYPT_MODE, cryptoKey, new GCMParameterSpec(TAG_LENGTH_BIT, iv));
        } catch (Exception e) {
            throw new IOException("Unable to AES decrypt the data", e);
        }

        // have to copy out bytes, we reuse the reader byte array!
        buffer.readBytes(reader.getBuffer(), 0, length);

        if (DEBUG) {
            String ivString = ByteBufUtil.hexDump(iv, 0, IV_LENGTH_BYTE);
            String crypto = ByteBufUtil.hexDump(reader.getBuffer(), 0, length);
            logger.error("IV: (12)" + OS.LINE_SEPARATOR + ivString +
                         OS.LINE_SEPARATOR + "CRYPTO: (" + length + ")" + OS.LINE_SEPARATOR + crypto);
        }

        int decryptedLength;
        try {
            decryptedLength = cipher.doFinal(reader.getBuffer(),0, length, temp, 0);
        } catch (Exception e) {
           throw new IOException("Unable to AES decrypt the data", e);
        }

        ///////// decompress data -- as it's ALWAYS compressed

        // get the decompressed length (at the beginning of the array)
        final int uncompressedLength = OptimizeUtilsByteArray.readInt(temp, true);

        // where does our data start, AFTER the length field
        int start = OptimizeUtilsByteArray.intLength(uncompressedLength, true); // because 1-4 bytes for the uncompressed size;

        // LZ4 decompress, requires the size of the ORIGINAL length (because we use the FAST decompressor
        reader.reset();
        decompressor.decompress(temp, start, reader.getBuffer(), 0, uncompressedLength);
        reader.setLimit(uncompressedLength);

        if (DEBUG) {
            int endWithoutUncompressedLength = decryptedLength - start;
            String compressed = ByteBufUtil.hexDump(temp, start, endWithoutUncompressedLength);
            String orig = ByteBufUtil.hexDump(reader.getBuffer(), 0, uncompressedLength);
            logger.error("COMPRESSED: (" + endWithoutUncompressedLength + ")" + OS.LINE_SEPARATOR + compressed +
                         OS.LINE_SEPARATOR +
                         "ORIG: (" + uncompressedLength + ")" + OS.LINE_SEPARATOR + orig);
        }

        // read the object from the buffer.
        return read(connection, reader);
    }

    public
    NetworkSerializationManager getSerializationManager() {
        return serializationManager;
    }
}
