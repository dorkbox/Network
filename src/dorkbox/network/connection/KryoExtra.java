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

import com.esotericsoftware.kryo.Kryo;
import dorkbox.network.pipeline.ByteBufInput;
import dorkbox.network.pipeline.ByteBufOutput;
import dorkbox.util.bytes.BigEndian;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;
import org.bouncycastle.crypto.engines.AESFastEngine;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.slf4j.Logger;

import java.io.IOException;

/**
 * Nothing in this class is thread safe
 */
public
class KryoExtra extends Kryo {
    // this is the minimum size that LZ4 can compress. Anything smaller than this will result in an output LARGER than the input.
    private static final int LZ4_COMPRESSION_MIN_SIZE = 32;

    /**
     * bit masks
     */
    private static final byte compress = (byte) 1;
    static final byte crypto = (byte) (1 << 1);

    // snappycomp   :       7.534 micros/op;  518.5 MB/s (output: 55.1%)
    // snappyuncomp :       1.391 micros/op; 2808.1 MB/s
    // lz4comp      :       6.210 micros/op;  629.0 MB/s (output: 55.4%)
    // lz4uncomp    :       0.641 micros/op; 6097.9 MB/s
    private static final LZ4Factory factory = LZ4Factory.fastestInstance();

    // for kryo serialization
    private final ByteBufInput reader = new ByteBufInput();
    private final ByteBufOutput writer = new ByteBufOutput();

    // volatile to provide object visibility for entire class
    public volatile ConnectionImpl connection;

    private final GCMBlockCipher aesEngine = new GCMBlockCipher(new AESFastEngine());


    // writing data
    private final ByteBuf tempBuffer = Unpooled.buffer(EndPoint.udpMaxSize);
    private LZ4Compressor compressor = factory.fastCompressor();

    private int compressOutputLength = -1;
    private byte[] compressOutput;

    private int cryptoOutputLength = -1;
    private byte[] cryptoOutput;


    // reading data
    private LZ4FastDecompressor decompressor = factory.fastDecompressor();

    private int decryptOutputLength = -1;
    private byte[] decryptOutput;
    private ByteBuf decryptBuf;

    private int decompressOutputLength = -1;
    private byte[] decompressOutput;
    private ByteBuf decompressBuf;


    public
    KryoExtra() {
    }

    /**
     * @param connection if != null, perform crypto on the data.
     */
    public synchronized
    void write(final ConnectionImpl connection, final ByteBuf buffer, final Object message, final Logger logger) throws IOException {
        // connection will ALWAYS be of type Connection or NULL.
        // used by RMI/some serializers to determine which connection wrote this object
        // NOTE: this is only valid in the context of this thread, which RMI stuff is accessed in -- so this is SAFE for RMI
        this.connection = connection;


        // do we compress + crypto the data?  (don't always need it, specifically during connection INIT)
        if (connection == null) {
            // magic byte
            buffer.writeByte(0);

            // write the object to the NORMAL output buffer!
            writer.setBuffer(buffer);

            writeClassAndObject(writer, message);
        } else {
            final boolean traceEnabled = logger.isTraceEnabled();
            byte magicByte = (byte) 0x00000000;

            ByteBuf objectOutputBuffer = this.tempBuffer;
            objectOutputBuffer.clear(); // always have to reset everything

            // write the object to a TEMP buffer! this will be compressed
            writer.setBuffer(objectOutputBuffer);

            writeClassAndObject(writer, message);

            // save off how much data the object took + magic byte
            int length = objectOutputBuffer.writerIndex();


            // NOTE: compression and encryption MUST work with byte[] because they use JNI!
            // Realistically, it is impossible to get the backing arrays out of a Heap Buffer once they are resized and begin to use
            // sliced. It's lame that there is a "double copy" of bytes here, but I don't know how to avoid it...
            // see:   https://stackoverflow.com/questions/19296386/netty-java-getting-data-from-bytebuf

            byte[] inputArray;
            int inputOffset;

            //noinspection Duplicates
            if (objectOutputBuffer.hasArray()) {
                // Even if a ByteBuf has a backing array (i.e. buf.hasArray() returns true), the following isn't necessarily true because
                // the buffer might be a slice of other buffer or a pooled buffer:

                if (objectOutputBuffer.array()[0] == objectOutputBuffer.getByte(0) &&
                    objectOutputBuffer.array().length == objectOutputBuffer.capacity()) {

                    // we can use it...
                    inputArray = objectOutputBuffer.array();
                    inputOffset = objectOutputBuffer.arrayOffset();
                } else {
                    // we can NOT use it.
                    inputArray = new byte[length];
                    objectOutputBuffer.getBytes(objectOutputBuffer.readerIndex(), inputArray);
                    inputOffset = 0;
                }
            } else {
                inputArray = new byte[length];
                objectOutputBuffer.getBytes(objectOutputBuffer.readerIndex(), inputArray);
                inputOffset = 0;
            }

            if (length > LZ4_COMPRESSION_MIN_SIZE) {
                if (traceEnabled) {
                    logger.trace("Compressing data {}", connection);
                }

                byte[] compressOutput = this.compressOutput;

                int maxCompressedLength = compressor.maxCompressedLength(length);

                // add 4 so there is room to write the compressed size to the buffer
                int maxCompressedLengthWithOffset = maxCompressedLength + 4;

                // lazy initialize the compression output buffer
                if (maxCompressedLengthWithOffset > compressOutputLength) {
                    compressOutputLength = maxCompressedLengthWithOffset;
                    compressOutput = new byte[maxCompressedLengthWithOffset];
                    this.compressOutput = compressOutput;
                }


                // LZ4 compress. output offset 4 to leave room for length of tempOutput data
                int compressedLength = compressor.compress(inputArray, inputOffset, length, compressOutput, 4, maxCompressedLength);

                // bytes can now be written to, because our compressed data is stored in a temp array.
                // ONLY do this if our compressed length is LESS than our uncompressed length.
                if (compressedLength < length) {
                    // now write the ORIGINAL (uncompressed) length to the front of the byte array. This is so we can use the FAST
                    // decompress version
                    BigEndian.Int_.toBytes(length, compressOutput);

                    magicByte |= compress;

                    // corrected length
                    length = compressedLength + 4; // +4 for the uncompressed size bytes

                    // correct input.  compression output is now encryption input
                    inputArray = compressOutput;
                    inputOffset = 0;
                }
            }

            if (traceEnabled) {
                logger.trace("AES encrypting data {}", connection);
            }


            final GCMBlockCipher aes = this.aesEngine;

            // TODO: AES IV must be a NONCE! can use an initial IV, and then have a counter or something with a hash of counter+initialIV to make the new IV
            aes.reset();
            aes.init(true, connection.getCryptoParameters());

            byte[] cryptoOutput;

            // lazy initialize the crypto output buffer
            int cryptoSize = aes.getOutputSize(length);

            // 'output' is the temp byte array
            if (cryptoSize > cryptoOutputLength) {
                cryptoOutputLength = cryptoSize;
                cryptoOutput = new byte[cryptoSize];
                this.cryptoOutput = cryptoOutput;
            } else {
                cryptoOutput = this.cryptoOutput;
            }

            int encryptedLength = aes.processBytes(inputArray, inputOffset, length, cryptoOutput, 0);

            try {
                // authentication tag for GCM
                encryptedLength += aes.doFinal(cryptoOutput, encryptedLength);
            } catch (Exception e) {
                throw new IOException("Unable to AES encrypt the data", e);
            }

            magicByte |= crypto;

            // write out the "magic" byte.
            buffer.writeByte(magicByte);

            // have to copy over the orig data, because we used the temp buffer
            buffer.writeBytes(cryptoOutput, 0, encryptedLength);
        }
    }

    @SuppressWarnings("Duplicates")
    public synchronized
    Object read(final ConnectionImpl connection, final ByteBuf buffer, int length, final Logger logger) throws IOException {
        // connection will ALWAYS be of type IConnection or NULL.
        // used by RMI/some serializers to determine which connection read this object
        // NOTE: this is only valid in the context of this thread, which RMI stuff is accessed in -- so this is SAFE for RMI
        this.connection = connection;


        ////////////////
        // Note: we CANNOT write BACK to the buffer as "temp" storage, since there could be additional data on it!
        ////////////////

        ByteBuf inputBuf = buffer;

        // read off the magic byte
        final byte magicByte = buffer.readByte();

        // compression can ONLY happen if it's ALSO crypto'd
        if ((magicByte & crypto) == crypto) {
            // have to adjust for the magic byte
            length -= 1;

            // it's ALWAYS encrypted, but MAYBE compressed
            final boolean isCompressed = (magicByte & compress) == compress;


            if (logger.isTraceEnabled()) {
                logger.trace("AES decrypting data {}", connection);
            }

            // NOTE: compression and encryption MUST work with byte[] because they use JNI!
            // Realistically, it is impossible to get the backing arrays out of a Heap Buffer once they are resized and begin to use
            // sliced. It's lame that there is a "double copy" of bytes here, but I don't know how to avoid it...
            // see:   https://stackoverflow.com/questions/19296386/netty-java-getting-data-from-bytebuf

            byte[] inputArray;
            int inputOffset;

            if (inputBuf.hasArray()) {
                // Even if a ByteBuf has a backing array (i.e. buf.hasArray() returns true), the following isn't necessarily true because
                // the buffer might be a slice of other buffer or a pooled buffer:

                if (inputBuf.array()[0] == inputBuf.getByte(0) &&
                    inputBuf.array().length == inputBuf.capacity()) {

                    // we can use it...
                    inputArray = inputBuf.array();
                    inputOffset = inputBuf.arrayOffset();
                } else {
                    // we can NOT use it.
                    inputArray = new byte[length];
                    inputBuf.getBytes(inputBuf.readerIndex(), inputArray);
                    inputOffset = 0;
                }
            } else {
                inputArray = new byte[length];
                inputBuf.getBytes(inputBuf.readerIndex(), inputArray);
                inputOffset = 0;
            }

            // have to make sure to set the position of the buffer, since our conversion to array DOES NOT set the new reader index.
            buffer.readerIndex(buffer.readerIndex() + length);

            final GCMBlockCipher aes = this.aesEngine;
            aes.reset();
            aes.init(false, connection.getCryptoParameters());

            int cryptoSize = aes.getOutputSize(length);

            // lazy initialize the decrypt output buffer
            byte[] decryptOutputArray;
            if (cryptoSize > decryptOutputLength) {
                decryptOutputLength = cryptoSize;
                decryptOutputArray = new byte[cryptoSize];
                this.decryptOutput = decryptOutputArray;

                decryptBuf = Unpooled.wrappedBuffer(decryptOutputArray);
            } else {
                decryptOutputArray = this.decryptOutput;
            }

            int decryptedLength = aes.processBytes(inputArray, inputOffset, length, decryptOutputArray, 0);

            try {
                // authentication tag for GCM
                decryptedLength += aes.doFinal(decryptOutputArray, decryptedLength);
            } catch (Exception e) {
                throw new IOException("Unable to AES decrypt the data", e);
            }

            if (isCompressed) {
                inputArray = decryptOutputArray;
                inputOffset = 4; // because 4 bytes for the decompressed size

                // get the decompressed length (at the beginning of the array)
                final int uncompressedLength = BigEndian.Int_.from(inputArray);

                byte[] decompressOutputArray = this.decompressOutput;
                if (uncompressedLength > decompressOutputLength) {
                    decompressOutputLength = uncompressedLength;
                    decompressOutputArray = new byte[uncompressedLength];
                    this.decompressOutput = decompressOutputArray;

                    decompressBuf = Unpooled.wrappedBuffer(decompressOutputArray);  // so we can read via kryo
                }

                // LZ4 decompress, requires the size of the ORIGINAL length (because we use the FAST decompressor
                decompressor.decompress(inputArray, inputOffset, decompressOutputArray, 0, uncompressedLength);

                decompressBuf.setIndex(0, uncompressedLength);
                inputBuf = decompressBuf;
            }
            else {
                decryptBuf.setIndex(0, decryptedLength);
                inputBuf = decryptBuf;
            }
        }

        // read the object from the buffer.
        reader.setBuffer(inputBuf);

        return readClassAndObject(reader); // this properly sets the readerIndex, but only if it's the correct buffer

    }

    @Override
    protected
    void finalize() throws Throwable {
        if (decompressBuf != null) {
            decompressBuf.release();
        }

        if (decryptBuf != null) {
            decryptBuf.release();
        }

        super.finalize();
    }
}
