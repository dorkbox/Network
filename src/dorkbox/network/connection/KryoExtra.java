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
import dorkbox.util.bytes.OptimizeUtilsByteArray;
import dorkbox.util.bytes.OptimizeUtilsByteBuf;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;
import org.bouncycastle.crypto.engines.AESFastEngine;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.params.ParametersWithIV;

import java.io.IOException;

/**
 * Nothing in this class is thread safe
 */
public
class KryoExtra extends Kryo {
    /**
     * bit masks
     */
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

    private int inputArrayLength = -1;
    private byte[] inputArray;

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

    public synchronized
    void write(final ByteBuf buffer, final Object message) throws IOException {
        // connection will always be NULL during connection initialization
        this.connection = null;

        // during INIT and handshake, we don't use connection encryption/compression
        // magic byte
        buffer.writeByte(0);

        // write the object to the NORMAL output buffer!
        writer.setBuffer(buffer);

        writeClassAndObject(writer, message);
    }

    public synchronized
    Object read(final ByteBuf buffer) throws IOException {
        // connection will always be NULL during connection initialization
        this.connection = null;


        ////////////////
        // Note: we CANNOT write BACK to the buffer as "temp" storage, since there could be additional data on it!
        ////////////////

        ByteBuf inputBuf = buffer;

        // read off the magic byte
        final byte magicByte = buffer.readByte();

        // read the object from the buffer.
        reader.setBuffer(inputBuf);

        return readClassAndObject(reader); // this properly sets the readerIndex, but only if it's the correct buffer
    }


    public synchronized
    void writeCrypto(final ConnectionImpl connection, final ByteBuf buffer, final Object message) throws IOException {
        // required by RMI and some serializers to determine which connection wrote (or has info about) this object
        this.connection = connection;

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

        // Even if a ByteBuf has a backing array (i.e. buf.hasArray() returns true), the using it isn't always possible because
        // the buffer might be a slice of other buffer or a pooled buffer:
        //noinspection Duplicates
        if (objectOutputBuffer.hasArray() &&
            objectOutputBuffer.array()[0] == objectOutputBuffer.getByte(0) &&
            objectOutputBuffer.array().length == objectOutputBuffer.capacity()) {

            // we can use it...
            inputArray = objectOutputBuffer.array();
            inputArrayLength = -1; // this is so we don't REUSE this array accidentally!
            inputOffset = objectOutputBuffer.arrayOffset();
        }
        else {
            // we can NOT use it.
            if (length > inputArrayLength) {
                inputArrayLength = length;
                inputArray = new byte[length];
                this.inputArray = inputArray;
            }
            else {
                inputArray = this.inputArray;
            }

            objectOutputBuffer.getBytes(objectOutputBuffer.readerIndex(), inputArray, 0, length);
            inputOffset = 0;
        }


        ////////// compressing data
        // we ALWAYS compress our data stream -- because of how AES-GCM pads data out, the small input (that would result in a larger
        // output), will be negated by the increase in size by the encryption

        byte[] compressOutput = this.compressOutput;

        int maxLengthLengthOffset = 5;
        int maxCompressedLength = compressor.maxCompressedLength(length);

        // add 5 so there is room to write the compressed size to the buffer
        int maxCompressedLengthWithOffset = maxCompressedLength + maxLengthLengthOffset;

        // lazy initialize the compression output buffer
        if (maxCompressedLengthWithOffset > compressOutputLength) {
            compressOutputLength = maxCompressedLengthWithOffset;
            compressOutput = new byte[maxCompressedLengthWithOffset];
            this.compressOutput = compressOutput;
        }



        // LZ4 compress. output offset max 5 bytes to leave room for length of tempOutput data
        int compressedLength = compressor.compress(inputArray, inputOffset, length, compressOutput, maxLengthLengthOffset, maxCompressedLength);

        // bytes can now be written to, because our compressed data is stored in a temp array.

        final int lengthLength = OptimizeUtilsByteArray.intLength(length, true);

        // correct input.  compression output is now encryption input
        inputArray = compressOutput;
        inputOffset = maxLengthLengthOffset - lengthLength;


        // now write the ORIGINAL (uncompressed) length to the front of the byte array. This is so we can use the FAST decompress version
        OptimizeUtilsByteArray.writeInt(inputArray, length, true, inputOffset);

        // correct length for encryption
        length = compressedLength + lengthLength; // +1 to +5 for the uncompressed size bytes



        /////// encrypting data.
        final long nextGcmSequence = connection.getNextGcmSequence();

        // this is a threadlocal, so that we don't clobber other threads that are performing crypto on the same connection at the same time
        final ParametersWithIV cryptoParameters = connection.getCryptoParameters();
        BigEndian.Long_.toBytes(nextGcmSequence, cryptoParameters.getIV(), 4); // put our counter into the IV

        final GCMBlockCipher aes = this.aesEngine;
        aes.reset();
        aes.init(true, cryptoParameters);

        byte[] cryptoOutput;

        // lazy initialize the crypto output buffer
        int cryptoSize = length + 16;   // from:  aes.getOutputSize(length);

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

        // write out the "magic" byte.
        buffer.writeByte(crypto);

        // write out our GCM counter
        OptimizeUtilsByteBuf.writeLong(buffer, nextGcmSequence, true);

//        System.err.println("out " + gcmIVCounter);

        // have to copy over the orig data, because we used the temp buffer
        buffer.writeBytes(cryptoOutput, 0, encryptedLength);
    }

    public
    Object readCrypto(final ConnectionImpl connection, final ByteBuf buffer, int length) throws IOException {
        // required by RMI and some serializers to determine which connection wrote (or has info about) this object
        this.connection = connection;


        ////////////////
        // Note: we CANNOT write BACK to the buffer as "temp" storage, since there could be additional data on it!
        ////////////////

        ByteBuf inputBuf = buffer;

        // read off the magic byte
        final byte magicByte = buffer.readByte();

        final long gcmIVCounter = OptimizeUtilsByteBuf.readLong(buffer, true);
//        System.err.println("in " + gcmIVCounter);

        // compression can ONLY happen if it's ALSO crypto'd

        // have to adjust for the magic byte and the gcmIVCounter
        length = length - 1 - OptimizeUtilsByteArray.longLength(gcmIVCounter, true);


        /////////// decrypting data

        // NOTE: compression and encryption MUST work with byte[] because they use JNI!
        // Realistically, it is impossible to get the backing arrays out of a Heap Buffer once they are resized and begin to use
        // sliced. It's lame that there is a "double copy" of bytes here, but I don't know how to avoid it...
        // see:   https://stackoverflow.com/questions/19296386/netty-java-getting-data-from-bytebuf

        byte[] inputArray;
        int inputOffset;

        // Even if a ByteBuf has a backing array (i.e. buf.hasArray() returns true), the using it isn't always possible because
        // the buffer might be a slice of other buffer or a pooled buffer:
        //noinspection Duplicates
        if (inputBuf.hasArray() &&
            inputBuf.array()[0] == inputBuf.getByte(0) &&
            inputBuf.array().length == inputBuf.capacity()) {

            // we can use it...
            inputArray = inputBuf.array();
            inputArrayLength = -1; // this is so we don't REUSE this array accidentally!
            inputOffset = inputBuf.arrayOffset();
        }
        else {
            // we can NOT use it.
            if (length > inputArrayLength) {
                inputArrayLength = length;
                inputArray = new byte[length];
                this.inputArray = inputArray;
            }
            else {
                inputArray = this.inputArray;
            }

            inputBuf.getBytes(inputBuf.readerIndex(), inputArray, 0, length);
            inputOffset = 0;
        }



        // have to make sure to set the position of the buffer, since our conversion to array DOES NOT set the new reader index.
        buffer.readerIndex(buffer.readerIndex() + length);

        // this is a threadlocal, so that we don't clobber other threads that are performing crypto on the same connection at the same time
        final ParametersWithIV cryptoParameters = connection.getCryptoParameters();
        BigEndian.Long_.toBytes(gcmIVCounter, cryptoParameters.getIV(), 4); // put our counter into the IV

        final GCMBlockCipher aes = this.aesEngine;
        aes.reset();
        aes.init(false, cryptoParameters);

        int cryptoSize = length - 16; // from:  aes.getOutputSize(length);

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

        ///////// decompress data -- as it's ALWAYS compressed

        // get the decompressed length (at the beginning of the array)
        inputArray = decryptOutputArray;
        final int uncompressedLength = OptimizeUtilsByteArray.readInt(inputArray, true);
        inputOffset = OptimizeUtilsByteArray.intLength(uncompressedLength, true); // because 1-5 bytes for the decompressed size


        byte[] decompressOutputArray = this.decompressOutput;
        if (uncompressedLength > decompressOutputLength) {
            decompressOutputLength = uncompressedLength;
            decompressOutputArray = new byte[uncompressedLength];
            this.decompressOutput = decompressOutputArray;

            decompressBuf = Unpooled.wrappedBuffer(decompressOutputArray);  // so we can read via kryo
        }
        inputBuf = decompressBuf;

        // LZ4 decompress, requires the size of the ORIGINAL length (because we use the FAST decompressor
        decompressor.decompress(inputArray, inputOffset, decompressOutputArray, 0, uncompressedLength);

        inputBuf.setIndex(0, uncompressedLength);

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
