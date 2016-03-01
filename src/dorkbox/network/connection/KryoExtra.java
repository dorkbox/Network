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

import java.io.IOException;

public
class KryoExtra extends Kryo {

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(KryoExtra.class);
    private static final ByteBuf nullByteBuf = (ByteBuf) null;

    // this is the minimum size that LZ4 can compress. Anything smaller than this will result in an output LARGER than the input.
    private static final int LZ4_COMPRESSION_MIN_SIZE = 32;

    /**
     * bit masks
     */
    private static final byte compress = (byte) 1;
    static final byte crypto = (byte) (1 << 1);

    private static LZ4Factory factory = LZ4Factory.fastestInstance();

    // for kryo serialization
    private final ByteBufInput reader = new ByteBufInput();
    private final ByteBufOutput writer = new ByteBufOutput();

    // not thread safe
    public ConnectionImpl connection;

    private final GCMBlockCipher aesEngine;

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

//    private static XXHashFactory hashFactory = XXHashFactory.fastestInstance();

    public
    KryoExtra() {
//        final Checksum checksumIn;
//        final Checksum checksumOut;
//        if (OS.is64bit()) {
//            long seed = 0x3f79759cb27bf824L; // this has to stay the same
//            checksumIn = hashFactory.newStreamingHash64(seed)
//                                    .asChecksum();
//            checksumOut = hashFactory.newStreamingHash64(seed)
//                                     .asChecksum();
//
//        }
//        else {
//            int seed = 0xF51BA30E; // this has to stay the same
//            checksumIn = hashFactory.newStreamingHash32(seed)
//                                    .asChecksum();
//            checksumOut = hashFactory.newStreamingHash32(seed)
//                                     .asChecksum();
//        }
//


//        > Our data is partitionaned into 64 blocks which are compressed separetely.
//
//        64 bytes blocks ? This would be much too small.
//
//
//        > Would a larger block size improve the compression ratio?
//
//        Probably.
//        When compressing data using independent blocks, you can observe compression ratio gains up to 1 MB blocks.
//        Beyond that, benefits become less ans less visible.
//
//
//        > Is there an optimal block size?
//
//        For independent blocks, 64KB is considered optimal "small" size.
//        Between 4 KB & 64 KB, it is "very small", but still manageable.
//        Anything below 4 KB is starting to miss a lost of compression opportunity. Ratio will plummet.
//
//
//        > Does anybody have recommendations on how to improve the compression ration?
//
//        Anytime you input data consists of
//        tables with fixed length cells,
//                        blosc should be tested : if offers big opportunities for compression savings.


//        ➜  leveldb git:(lz4) ✗ ./db_bench
//        LevelDB:    version 1.7
//        Keys:       16 bytes each
//        Values:     100 bytes each (50 bytes after compression)
//        Entries:    1000000
//        RawSize:    110.6 MB (estimated)
//        FileSize:   62.9 MB (estimated)
//                        ------------------------------------------------
//        snappycomp   :       7.534 micros/op;  518.5 MB/s (output: 55.1%)
//        snappyuncomp :       1.391 micros/op; 2808.1 MB/s
//        lz4comp      :       6.210 micros/op;  629.0 MB/s (output: 55.4%)
//        lz4uncomp    :       0.641 micros/op; 6097.9 MB/s

        this.aesEngine = new GCMBlockCipher(new AESFastEngine());
    }

    public
    void write(final ConnectionImpl connection, final ByteBuf buffer, final Object message, final boolean doCrypto) throws IOException {
        // do we compress + crypto the data?  (don't always need it, specifically during connection INIT)
        if (connection == null || !doCrypto) {
            // magic byte
            buffer.writeByte(0);

            // write the object to the NORMAL output buffer!
            writer.setBuffer(buffer);

            // connection will ALWAYS be of type Connection or NULL.
            // used by RMI/some serializers to determine which connection wrote this object
            // NOTE: this is only valid in the context of this thread, which RMI stuff is accessed in -- so this is SAFE for RMI
            this.connection = connection;
            writeClassAndObject(writer, message);
        } else {
            byte magicByte = (byte) 0x00000000;

            ByteBuf objectOutputBuffer = this.tempBuffer;
            objectOutputBuffer.clear(); // always have to reset everything

            // write the object to a TEMP buffer! this will be compressed
            writer.setBuffer(objectOutputBuffer);

            // connection will ALWAYS be of type Connection or NULL.
            // used by RMI/some serializers to determine which connection wrote this object
            // NOTE: this is only valid in the context of this thread, which RMI stuff is accessed in -- so this is SAFE for RMI
            this.connection = connection;

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
                if (logger.isTraceEnabled()) {
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
                    // now write the ORIGINAL (uncompressed) length to the front of the byte array. This is so we can use the FAST version
                    BigEndian.Int_.toBytes(length, compressOutput);

                    magicByte |= compress;

                    // corrected length
                    length = compressedLength + 4; // +4 for the uncompressed size bytes

                    // correct input.  compression output is now encryption input
                    inputArray = compressOutput;
                    inputOffset = 0;
                }
            }


            if (logger.isTraceEnabled()) {
                logger.trace("AES encrypting data {}", connection);
            }


            final GCMBlockCipher aes = this.aesEngine;

            // TODO: AES IV must be a NONCE! can use an initial IV, and then have a counter or something with a hash of counter+initialIV to make the new IV
            aes.reset();
            aes.init(true, connection.getCryptoParameters());

            byte[] cryptoOutput = this.cryptoOutput;

            // lazy initialize the crypto output buffer
            int cryptoSize = aes.getOutputSize(length);

            // 'output' is the temp byte array
            if (cryptoSize > cryptoOutputLength) {
                cryptoOutputLength = cryptoSize;
                cryptoOutput = new byte[cryptoSize];
                this.cryptoOutput = cryptoOutput;
            }

            //System.err.println("out orig: " + length + "  first/last " + inputArray[inputOffset] + " " + inputArray[length-1]);
            int actualLength = aes.processBytes(inputArray, inputOffset, length, cryptoOutput, 0);

            try {
                // authentication tag for GCM
                actualLength += aes.doFinal(cryptoOutput, actualLength);
            } catch (Exception e) {
                throw new IOException("Unable to AES encrypt the data", e);
            }

            magicByte |= crypto;

            // write out the "magic" byte.
            buffer.writeByte(magicByte);

            // have to copy over the orig data, because we used the temp buffer
            buffer.writeBytes(cryptoOutput, 0, actualLength);
        }
    }

    @SuppressWarnings("Duplicates")
    public
    Object read(final ConnectionImpl connection, final ByteBuf buffer, int length) throws IOException {
        // we cannot use the buffer as a "temp" storage, because there is other data on it

        // read off the magic byte
        final byte magicByte = buffer.readByte();
        ByteBuf inputBuf = buffer;


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


            final GCMBlockCipher aes = this.aesEngine;
            aes.reset();
            aes.init(false, connection.getCryptoParameters());

            int cryptoSize = aes.getOutputSize(length);

            // lazy initialize the decrypt output buffer
            byte[] decryptOutputArray = this.decryptOutput;
            if (cryptoSize > decryptOutputLength) {
                decryptOutputLength = cryptoSize;
                decryptOutputArray = new byte[cryptoSize];
                this.decryptOutput = decryptOutputArray;

                decryptBuf = Unpooled.wrappedBuffer(decryptOutputArray);
            }

            int decryptedLength = aes.processBytes(inputArray, inputOffset, length, decryptOutputArray, 0);

            try {
                // authentication tag for GCM (since we are DECRYPTING, the original 'actualLength' is the correct one.)
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

        // connection will ALWAYS be of type IConnection or NULL.
        // used by RMI/some serializers to determine which connection read this object
        // NOTE: this is only valid in the context of this thread, which RMI stuff is accessed in -- so this is SAFE for RMI
        this.connection = connection;

        return readClassAndObject(reader);
    }

    public final
    void releaseWrite() {
        // release/reset connection resources
        writer.setBuffer(nullByteBuf);
        connection = null;
    }

    public final
    void releaseRead() {
        // release/reset connection resources
        reader.setBuffer(nullByteBuf);
        connection = null;
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
