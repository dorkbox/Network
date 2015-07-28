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

import com.esotericsoftware.kryo.*;
import com.esotericsoftware.kryo.factories.ReflectionSerializerFactory;
import com.esotericsoftware.kryo.factories.SerializerFactory;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.serializers.CollectionSerializer;
import com.esotericsoftware.kryo.serializers.FieldSerializer;
import com.esotericsoftware.kryo.util.MapReferenceResolver;
import dorkbox.network.connection.ping.PingMessage;
import dorkbox.network.rmi.*;
import dorkbox.network.util.CryptoSerializationManager;
import dorkbox.util.crypto.Crypto;
import dorkbox.util.objectPool.ObjectPool;
import dorkbox.util.objectPool.ObjectPoolFactory;
import dorkbox.util.objectPool.PoolableObject;
import dorkbox.util.serialization.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.handler.codec.compression.CompressionException;
import io.netty.handler.codec.compression.SnappyAccess;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.params.IESParameters;
import org.bouncycastle.crypto.params.IESWithCipherParameters;
import org.jctools.util.Pow2;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Threads reading/writing, it messes up a single instance.
 * it is possible to use a single kryo with the use of synchronize, however - that defeats the point of multi-threaded
 */
@SuppressWarnings({"unused", "StaticNonFinalField"})
public
class KryoCryptoSerializationManager implements CryptoSerializationManager {

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(KryoCryptoSerializationManager.class);
    private static final int capacity = Pow2.roundToPowerOfTwo(Runtime.getRuntime()
                                                                      .availableProcessors() * 32);

    private static final boolean ENABLE_SNAPPY = false;

    /**
     * The minimum amount that we'll consider actually attempting to compress.
     * This value is preamble + the minimum length our Snappy service will
     * compress (instead of just emitting a literal).
     */
    private static final int MIN_COMPRESSIBLE_LENGTH = 18;

    /**
     * bit masks
     */
    private static final int compression = 1;
    private static final int crypto = 1 << 1;

    // compression options
    static final int compressionLevel = 6;
    private static final ByteBuf NULL_BUFFER = null;

    /**
     * Specify if we want KRYO to use unsafe memory for serialization, or to use the ASM backend. Unsafe memory use is WAY faster, but is
     * limited to the "same endianess" on all endpoints, and unsafe DOES NOT work on android.
     */
    public static boolean useUnsafeMemory = false;

    private static final String OBJECT_ID = "objectID";
    private boolean initialized = false;

    /**
     * The default serialization manager. This is static, since serialization must be consistent within the JVM. This can be changed.
     */
    public static KryoCryptoSerializationManager DEFAULT = DEFAULT();


    public static
    KryoCryptoSerializationManager DEFAULT() {
        return DEFAULT(true, true);
    }

    public static
    KryoCryptoSerializationManager DEFAULT(final boolean references, final boolean registrationRequired) {
        // ignore fields that have the "@IgnoreSerialization" annotation.
        Collection<Class<? extends Annotation>> marks = new ArrayList<Class<? extends Annotation>>();
        marks.add(IgnoreSerialization.class);
        SerializerFactory disregardingFactory = new FieldAnnotationAwareSerializer.Factory(marks, true);

        final KryoCryptoSerializationManager serializationManager = new KryoCryptoSerializationManager(references,
                                                                                                       registrationRequired,
                                                                                                       disregardingFactory);

        serializationManager.register(PingMessage.class);
        serializationManager.register(byte[].class);

        serializationManager.register(IESParameters.class, new IesParametersSerializer());
        serializationManager.register(IESWithCipherParameters.class, new IesWithCipherParametersSerializer());
        serializationManager.register(ECPublicKeyParameters.class, new EccPublicKeySerializer());
        serializationManager.register(ECPrivateKeyParameters.class, new EccPrivateKeySerializer());
        serializationManager.register(dorkbox.network.connection.registration.Registration.class);

        // necessary for the transport of exceptions.
        serializationManager.register(ArrayList.class, new CollectionSerializer());
        serializationManager.register(StackTraceElement.class);
        serializationManager.register(StackTraceElement[].class);

        // extra serializers
        //noinspection ArraysAsListWithZeroOrOneArgument
        serializationManager.register(Arrays.asList("").getClass(), new ArraysAsListSerializer());

        UnmodifiableCollectionsSerializer.registerSerializers(serializationManager);

        return serializationManager;
    }

    @SuppressWarnings("unused")
    private static
    void compress(ByteBuf inputBuffer, ByteBuf outputBuffer, int length, Deflater compress) {

        byte[] in = new byte[inputBuffer.readableBytes()];
        inputBuffer.readBytes(in);

        compress.reset();
        compress.setInput(in);
        compress.finish();

        byte[] out = new byte[1024];
        int numBytes = out.length;
        while (numBytes == out.length) {
            numBytes = compress.deflate(out, 0, out.length);
            outputBuffer.writeBytes(out, 0, numBytes);
        }
    }


    private static
    void decompress(ByteBuf inputBuffer, ByteBuf outputBuffer, Inflater decompress) throws IOException {
        byte[] in = new byte[inputBuffer.readableBytes()];
        inputBuffer.readBytes(in);

        decompress.reset();
        decompress.setInput(in);

        byte[] out = new byte[1024];
        int numBytes = out.length;
        while (numBytes == out.length) {
            try {
                numBytes = decompress.inflate(out, 0, out.length);
            } catch (DataFormatException e) {
                logger.error("Error inflating data.", e);
                throw new IOException(e.getCause());
            }

            outputBuffer.writeBytes(out, 0, numBytes);
        }
    }

    private static
    void snappyCompress(ByteBuf inputBuffer, ByteBuf outputBuffer, int length, SnappyAccess snappy) {
        // compress the tempBuffer (which has our object serialized inside it)

        // If we have lots of available data, break it up into smaller chunks
        int dataLength = length;
        while (true) {
            final int lengthIdx = outputBuffer.writerIndex() + 1;
            if (dataLength < MIN_COMPRESSIBLE_LENGTH) {
                ByteBuf slice = inputBuffer.readSlice(dataLength);
                writeUnencodedChunk(slice, outputBuffer, dataLength);
                break;
            }

            outputBuffer.writeInt(0);
            if (dataLength > Short.MAX_VALUE) {
                ByteBuf slice = inputBuffer.readSlice(Short.MAX_VALUE);
                calculateAndWriteChecksum(slice, outputBuffer);
                snappy.encode(slice, outputBuffer, Short.MAX_VALUE);
                setChunkLength(outputBuffer, lengthIdx);
                dataLength -= Short.MAX_VALUE;
            }
            else {
                ByteBuf slice = inputBuffer.readSlice(dataLength);
                calculateAndWriteChecksum(slice, outputBuffer);
                snappy.encode(slice, outputBuffer, dataLength);
                setChunkLength(outputBuffer, lengthIdx);
                break;
            }
        }
    }

    private static
    void snappyDecompress(ByteBuf inputBuffer, ByteBuf outputBuffer, SnappyAccess snappy) throws IOException {
        try {
            int idx = inputBuffer.readerIndex();
            final int inSize = inputBuffer.writerIndex() - idx;
            if (inSize < 4) {
                // We need to be at least able to read the chunk type identifier (one byte),
                // and the length of the chunk (3 bytes) in order to proceed
                return;
            }

            final int chunkTypeVal = inputBuffer.getUnsignedByte(idx);
            final ChunkType chunkType = mapChunkType((byte) chunkTypeVal);
            final int chunkLength = ByteBufUtil.swapMedium(inputBuffer.getUnsignedMedium(idx + 1));

            switch (chunkType) {
                case RESERVED_SKIPPABLE:
                    if (inSize < 4 + chunkLength) {
                        // TODO: Don't keep skippable bytes
                        return;
                    }

                    inputBuffer.skipBytes(4 + chunkLength);
                    break;
                case RESERVED_UNSKIPPABLE:
                    // The spec mandates that reserved unskippable chunks must immediately
                    // return an error, as we must assume that we cannot decode the stream
                    // correctly
                    throw new CompressionException("Found reserved unskippable chunk type: 0x" + Integer.toHexString(chunkTypeVal));
                case UNCOMPRESSED_DATA:
                    if (chunkLength > 65536 + 4) {
                        throw new CompressionException("Received UNCOMPRESSED_DATA larger than 65540 bytes");
                    }

                    if (inSize < 4 + chunkLength) {
                        return;
                    }

                    inputBuffer.skipBytes(4);
                {
                    int checksum = ByteBufUtil.swapInt(inputBuffer.readInt());
                    validateChecksum(checksum, inputBuffer, inputBuffer.readerIndex(), chunkLength - 4);
                    outputBuffer.writeBytes(inputBuffer, chunkLength - 4);
                }
                break;
                case COMPRESSED_DATA:
                    if (inSize < 4 + chunkLength) {
                        return;
                    }

                    inputBuffer.skipBytes(4);
                {
                    int checksum = ByteBufUtil.swapInt(inputBuffer.readInt());
                    int oldWriterIndex = inputBuffer.writerIndex();
                    int uncompressedStart = outputBuffer.writerIndex();
                    try {
                        inputBuffer.writerIndex(inputBuffer.readerIndex() + chunkLength - 4);
                        snappy.decode(inputBuffer, outputBuffer);
                    } finally {
                        inputBuffer.writerIndex(oldWriterIndex);
                    }
                    int uncompressedLength = outputBuffer.writerIndex() - uncompressedStart;
                    validateChecksum(checksum, outputBuffer, uncompressedStart, uncompressedLength);
                }
                snappy.reset();
                break;
            }
        } catch (Exception e) {
            throw new IOException("Unable to decompress SNAPPY data!! " + e.getMessage());
        }
    }

    /**
     * Decodes the chunk type from the type tag byte.
     *
     * @param type The tag byte extracted from the stream
     * @return The appropriate {@link ChunkType}, defaulting to {@link ChunkType#RESERVED_UNSKIPPABLE}
     */
    static
    ChunkType mapChunkType(byte type) {
        if (type == 0) {
            return ChunkType.COMPRESSED_DATA;
        }
        else if (type == 1) {
            return ChunkType.UNCOMPRESSED_DATA;
        }
        else if ((type & 0x80) == 0x80) {
            return ChunkType.RESERVED_SKIPPABLE;
        }
        else {
            return ChunkType.RESERVED_UNSKIPPABLE;
        }
    }

    /**
     * Computes the CRC32 checksum of the supplied data, performs the "mask" operation
     * on the computed checksum, and then compares the resulting masked checksum to the
     * supplied checksum.
     *
     * @param expectedChecksum The checksum decoded from the stream to compare against
     * @param data             The input data to calculate the CRC32 checksum of
     * @throws CompressionException If the calculated and supplied checksums do not match
     */
    static
    void validateChecksum(int expectedChecksum, ByteBuf data) {
        validateChecksum(expectedChecksum, data, data.readerIndex(), data.readableBytes());
    }

    /**
     * Computes the CRC32 checksum of the supplied data, performs the "mask" operation
     * on the computed checksum, and then compares the resulting masked checksum to the
     * supplied checksum.
     *
     * @param expectedChecksum The checksum decoded from the stream to compare against
     * @param data             The input data to calculate the CRC32 checksum of
     * @throws CompressionException If the calculated and supplied checksums do not match
     */
    static
    void validateChecksum(int expectedChecksum, ByteBuf data, int offset, int length) {
        final int actualChecksum = SnappyAccess.calculateChecksum(data, offset, length);
        if (actualChecksum != expectedChecksum) {
            throw new CompressionException("mismatching checksum: " + Integer.toHexString(actualChecksum) +
                                           " (expected: " + Integer.toHexString(expectedChecksum) + ')');
        }
    }

    private static
    void writeUnencodedChunk(ByteBuf in, ByteBuf out, int dataLength) {
        out.writeByte(1);
        writeChunkLength(out, dataLength + 4);
        calculateAndWriteChecksum(in, out);
        out.writeBytes(in, dataLength);
    }

    private static
    void setChunkLength(ByteBuf out, int lengthIdx) {
        int chunkLength = out.writerIndex() - lengthIdx - 3;
        if (chunkLength >>> 24 != 0) {
            throw new CompressionException("compressed data too large: " + chunkLength);
        }
        out.setMedium(lengthIdx, ByteBufUtil.swapMedium(chunkLength));
    }

    /**
     * Writes the 2-byte chunk length to the output buffer.
     *
     * @param out         The buffer to write to
     * @param chunkLength The length to write
     */
    private static
    void writeChunkLength(ByteBuf out, int chunkLength) {
        out.writeMedium(ByteBufUtil.swapMedium(chunkLength));
    }

    /**
     * Calculates and writes the 4-byte checksum to the output buffer
     *
     * @param slice The data to calculate the checksum for
     * @param out   The output buffer to write the checksum to
     */
    private static
    void calculateAndWriteChecksum(ByteBuf slice, ByteBuf out) {
        out.writeInt(ByteBufUtil.swapInt(SnappyAccess.calculateChecksum(slice)));
    }

    final ObjectPool<Kryo> pool;

    /**
     * @param references           If true, each appearance of an object in the graph after the first is stored as an integer ordinal.
     *                             When set to true, {@link MapReferenceResolver} is used. This enables references to the same object and
     *                             cyclic graphs to be serialized, but typically adds overhead of one byte per object. (should be true)
     *                             <p/>
     * @param registrationRequired If true, an exception is thrown when an unregistered class is encountered.
     *                             <p/>
     *                             If false, when an unregistered class is encountered, its fully qualified class name will be serialized
     *                             and the {@link Kryo#addDefaultSerializer(Class, Class) default serializer} for the class used to
     *                             serialize the object. Subsequent appearances of the class within the same object graph are serialized
     *                             as an int id.
     *                             <p/>
     *                             Registered classes are serialized as an int id, avoiding the overhead of serializing the class name,
     *                             but have the drawback of needing to know the classes to be serialized up front.
     *                             <p/>
     * @param factory              Sets the serializer factory to use when no {@link Kryo#addDefaultSerializer(Class, Class) default
     *                             serializers} match an object's type. Default is {@link ReflectionSerializerFactory} with
     *                             {@link FieldSerializer}. @see Kryo#newDefaultSerializer(Class)
     *                             <p/>
     */
    public
    KryoCryptoSerializationManager(final boolean references, final boolean registrationRequired, final SerializerFactory factory) {
        // we have to use a custom queue, because we CANNOT have kryo's used that have not been properly "registered" with
        // different serializers/etc. This queue will properly block if it runs out of kryo's

        // This pool wil also pre-populate, so that we have the hit on startup, instead of on access
        // this is also so that our register methods can correctly register with all of the kryo instances
        pool = ObjectPoolFactory.create(new PoolableObject<Kryo>() {
            @Override
            public
            Kryo create() {
                KryoExtra kryo = new KryoExtra();

                // we HAVE to pre-allocate the KRYOs
                boolean useAsm = !useUnsafeMemory;

                kryo.setAsmEnabled(useAsm);
                kryo.setRegistrationRequired(registrationRequired);

                kryo.setReferences(references);

                if (factory != null) {
                    kryo.setDefaultSerializer(factory);
                }

                return kryo;
            }
        }, capacity);
    }

    /**
     * If the class is not registered and {@link Kryo#setRegistrationRequired(boolean)} is false, it is
     * automatically registered using the {@link Kryo#addDefaultSerializer(Class, Class) default serializer}.
     *
     * @throws IllegalArgumentException if the class is not registered and {@link Kryo#setRegistrationRequired(boolean)} is true.
     * @see ClassResolver#getRegistration(Class)
     */
    @Override
    public synchronized
    Registration getRegistration(Class<?> clazz) {
        Kryo kryo = null;
        Registration r = null;

        try {
            kryo = this.pool.take();
            r = kryo.getRegistration(clazz);
        } catch (InterruptedException e) {
            final String msg = "Interrupted during getRegistration()";
            logger.error(msg);
        } finally {
            if (kryo != null) {
                this.pool.release(kryo);
            }
        }

        return r;
    }

    /**
     * Registers the class using the lowest, next available integer ID and the
     * {@link Kryo#getDefaultSerializer(Class) default serializer}. If the class
     * is already registered, the existing entry is updated with the new
     * serializer. Registering a primitive also affects the corresponding
     * primitive wrapper.
     * <p/>
     * Because the ID assigned is affected by the IDs registered before it, the
     * order classes are registered is important when using this method. The
     * order must be the same at deserialization as it was for serialization.
     */
    @Override
    public synchronized
    void register(Class<?> clazz) {
        if (initialized) {
            throw new RuntimeException("Cannot register classes after initialization.");
        }

        Kryo kryo;
        try {
            for (int i = 0; i < capacity; i++) {
                kryo = this.pool.take();
                kryo.register(clazz);
                this.pool.release(kryo);
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Thread interrupted during kryo pool take/release cycle!", e);
        }
    }

    /**
     * Registers the class using the lowest, next available integer ID and the
     * specified serializer. If the class is already registered, the existing
     * entry is updated with the new serializer. Registering a primitive also
     * affects the corresponding primitive wrapper.
     * <p/>
     * Because the ID assigned is affected by the IDs registered before it, the
     * order classes are registered is important when using this method. The
     * order must be the same at deserialization as it was for serialization.
     */
    @Override
    public synchronized
    void register(Class<?> clazz, Serializer<?> serializer) {
        if (initialized) {
            throw new RuntimeException("Cannot register classes after initialization.");
        }

        Kryo kryo;
        try {
            for (int i = 0; i < capacity; i++) {
                kryo = this.pool.take();
                kryo.register(clazz, serializer);
                this.pool.release(kryo);
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Thread interrupted during kryo pool take/release cycle!", e);
        }
    }

    /**
     * Registers the class using the specified ID and serializer. If the ID is
     * already in use by the same type, the old entry is overwritten. If the ID
     * is already in use by a different type, a {@link KryoException} is thrown.
     * Registering a primitive also affects the corresponding primitive wrapper.
     * <p/>
     * IDs must be the same at deserialization as they were for serialization.
     *
     * @param id Must be >= 0. Smaller IDs are serialized more efficiently. IDs
     *           0-8 are used by default for primitive types and String, but
     *           these IDs can be repurposed.
     */
    @Override
    public synchronized
    void register(Class<?> clazz, Serializer<?> serializer, int id) {
        if (initialized) {
            throw new RuntimeException("Cannot register classes after initialization.");
        }

        Kryo kryo;
        try {
            for (int i = 0; i < capacity; i++) {
                kryo = this.pool.take();
                kryo.register(clazz, serializer, id);
                this.pool.release(kryo);
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Thread interrupted during kryo pool take/release cycle!", e);
        }
    }

    /**
     * Objects that we want to use RMI with must be accessed via an interface. This method configures the serialization of an
     * implementation to be serialized via the defined interface, as a RemoteObject (ie: proxy object). If the implementation
     * class is ALREADY registered, then it's registration will be overwritten by this one
     *
     * @param ifaceClass The interface used to access the remote object
     * @param implClass  The implementation class of the interface
     */
    @Override
    public synchronized
    <Iface, Impl extends Iface> void registerRemote(final Class<Iface> ifaceClass, final Class<Impl> implClass) {
        register(implClass, new RemoteObjectSerializer<Impl>());

        // After all common registrations, register OtherObjectImpl only on the server using the remote object interface ID.
        // This causes OtherObjectImpl to be serialized as OtherObject.
        int otherObjectID = getRegistration(implClass).getId();

        // this overrides the 'otherObjectID' with the specified class/serializer, so that when we WRITE this ID, the impl ID is written.
        register(ifaceClass, new RemoteObjectSerializer<Impl>(), otherObjectID);

        // we have to save this info in CachedMethod.
        CachedMethod.registerOverridden(ifaceClass, implClass);
    }

    /**
     * Necessary to register classes for RMI, only called once when the RMI bridge is created.
     */
    @Override
    public synchronized
    void initRmiSerialization() {
        if (initialized) {
            // already initialized.
            return;
        }

        InvokeMethodSerializer methodSerializer = new InvokeMethodSerializer();
        Serializer<Object> invocationSerializer = new Serializer<Object>() {
            @Override
            public
            void write(Kryo kryo, Output output, Object object) {
                RemoteInvocationHandler handler = (RemoteInvocationHandler) Proxy.getInvocationHandler(object);
                output.writeInt(handler.objectID, true);
            }

            @Override
            @SuppressWarnings({"unchecked", "AutoBoxing"})
            public
            Object read(Kryo kryo, Input input, Class<Object> type) {
                int objectID = input.readInt(true);

                KryoExtra kryoExtra = (KryoExtra) kryo;
                Object object = kryoExtra.connection.getRegisteredObject(objectID);

                if (object == null) {
                    logger.error("Unknown object ID in RMI ObjectSpace: {}", objectID);
                }
                return object;
            }
        };

        Kryo kryo;
        try {
            for (int i = 0; i < capacity; i++) {
                kryo = this.pool.take();

                kryo.register(Class.class);
                kryo.register(RmiRegistration.class);
                kryo.register(InvokeMethod.class, methodSerializer);
                kryo.register(Object[].class);

                FieldSerializer<InvokeMethodResult> resultSerializer = new FieldSerializer<InvokeMethodResult>(kryo, InvokeMethodResult.class) {
                    @Override
                    public
                    void write(Kryo kryo, Output output, InvokeMethodResult result) {
                        super.write(kryo, output, result);
                        output.writeInt(result.objectID, true);
                    }

                    @Override
                    public
                    InvokeMethodResult read(Kryo kryo, Input input, Class<InvokeMethodResult> type) {
                        InvokeMethodResult result = super.read(kryo, input, type);
                        result.objectID = input.readInt(true);
                        return result;
                    }
                };
                resultSerializer.removeField(OBJECT_ID);
                kryo.register(InvokeMethodResult.class, resultSerializer);
                kryo.register(InvocationHandler.class, invocationSerializer);

                this.pool.release(kryo);
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Thread interrupted during kryo pool take/release cycle!", e);
        }
    }

    /**
     * Called when initialization is complete. This is to prevent (and recognize) out-of-order class/serializer registration.
     */
    public
    void finishInit() {
        initialized = true;
    }

    @Override
    public
    boolean initialized() {
        return initialized;
    }

    /**
     * Waits until a kryo is available to write, using CAS operations to prevent having to synchronize.
     * <p/>
     * No crypto and no sequence number
     * <p/>
     * There is a small speed penalty if there were no kryo's available to use.
     */
    @Override
    public final
    void write(ByteBuf buffer, Object message) throws IOException {
        write0(null, buffer, message, false);
    }

    /**
     * Reads an object from the buffer.
     * <p/>
     * No crypto and no sequence number
     *
     * @param length should ALWAYS be the length of the expected object!
     */
    @Override
    public final
    Object read(ByteBuf buffer, int length) throws IOException {
        return read0(null, buffer, length, false);
    }

    /**
     * Writes the class and object using an available kryo instance
     */
    @Override
    public
    void writeFullClassAndObject(final Logger logger, Output output, Object value) throws IOException {
        Kryo kryo = null;
        boolean prev = false;

        try {
            kryo = this.pool.take();
            prev = kryo.isRegistrationRequired();
            kryo.setRegistrationRequired(false);

            kryo.writeClassAndObject(output, value);
        } catch (Exception ex) {
            final String msg = "Unable to serialize buffer";
            if (logger != null) {
                logger.error(msg, ex);
            }
            throw new IOException(msg, ex);
        } finally {
            if (kryo != null) {
                kryo.setRegistrationRequired(prev);
                this.pool.release(kryo);
            }
        }
    }

    @Override
    public
    Object readFullClassAndObject(final Logger logger, final Input input) throws IOException {
        Kryo kryo = null;
        boolean prev = false;

        try {
            kryo = this.pool.take();
            prev = kryo.isRegistrationRequired();
            kryo.setRegistrationRequired(false);

            return kryo.readClassAndObject(input);
        } catch (Exception ex) {
            final String msg = "Unable to deserialize buffer";
            if (logger != null) {
                logger.error(msg, ex);
            }
            throw new IOException(msg, ex);
        } finally {
            if (kryo != null) {
                kryo.setRegistrationRequired(prev);
                this.pool.release(kryo);
            }
        }
    }

    @Override
    public
    Kryo take() throws InterruptedException {
        return this.pool.take();
    }

    @Override
    public
    void release(final Kryo kryo) {
        this.pool.release(kryo);
    }

    /**
     * Determines if this buffer is encrypted or not.
     */
    public static
    boolean isEncrypted(ByteBuf buffer) {
        // read off the magic byte
        byte magicByte = buffer.getByte(buffer.readerIndex());
        return (magicByte & crypto) == crypto;
    }

    /**
     * Waits until a kryo is available to write, using CAS operations to prevent having to synchronize.
     * <p/>
     * There is a small speed penalty if there were no kryo's available to use.
     */
    @Override
    public final
    void writeWithCryptoTcp(ConnectionImpl connection, ByteBuf buffer, Object message) throws IOException {
        write0(connection, buffer, message, true);
    }

    /**
     * Waits until a kryo is available to write, using CAS operations to prevent having to synchronize.
     * <p/>
     * There is a small speed penalty if there were no kryo's available to use.
     */
    @Override
    public final
    void writeWithCryptoUdp(ConnectionImpl connection, ByteBuf buffer, Object message) throws IOException {
        write0(connection, buffer, message, true);
    }

    /**
     * Reads an object from the buffer.
     * <p/>
     * Crypto + sequence number
     *
     * @param connection can be NULL
     * @param length     should ALWAYS be the length of the expected object!
     */
    @Override
    public final
    Object readWithCryptoTcp(ConnectionImpl connection, ByteBuf buffer, int length) throws IOException {
        return read0(connection, buffer, length, true);
    }

    /**
     * Reads an object from the buffer.
     * <p/>
     * Crypto + sequence number
     *
     * @param connection can be NULL
     * @param length     should ALWAYS be the length of the expected object!
     */
    @Override
    public final
    Object readWithCryptoUdp(ConnectionImpl connection, ByteBuf buffer, int length) throws IOException {
        return read0(connection, buffer, length, true);
    }

    /**
     * @param doCrypto true if we want to perform crypto on this data.
     */
    @SuppressWarnings("unchecked")
    private
    void write0(final ConnectionImpl connection, final ByteBuf buffer, final Object message, final boolean doCrypto) throws IOException {
        final KryoExtra kryo = (KryoExtra) this.pool.takeUninterruptibly();
        Logger logger2 = logger;

        if (kryo == null) {
            final String msg = "Unable to serialize buffer. Kryo was null (likely interrupted during pool.take())";
            logger2.error(msg);
            return;
        }

        try {
            byte magicByte = (byte) 0x00000000;

            ByteBuf bufferWithData = kryo.tmpBuffer1;
            ByteBuf bufferTempData = kryo.tmpBuffer2;

            bufferWithData.clear();
            bufferTempData.clear();

            // write the object to the TEMP buffer! this will be compressed with snappy
            kryo.output.setBuffer(bufferWithData);

            // connection will ALWAYS be of type Connection or NULL.
            // used by RMI/some serializers to determine which connection wrote this object
            // NOTE: this is only valid in the context of this thread, which RMI stuff is accessed in -- so this is SAFE for RMI
            kryo.connection = connection;

            kryo.writeClassAndObject(kryo.output, message);

            // save off how much data the object took + the length of the (possible) sequence.
            int length = bufferWithData.writerIndex();  // it started at ZERO (since it's written to the temp buffer.

            // snappy compression
            //noinspection StatementWithEmptyBody
            if (length > MIN_COMPRESSIBLE_LENGTH) {
                if (ENABLE_SNAPPY) {
                    snappyCompress(bufferWithData, bufferTempData, length, kryo.snappy);
                }
                else {
                    compress(bufferWithData, bufferTempData, length, kryo.deflater);
                }

                // check to make sure that it was WORTH compressing, like what I had before
                int compressedLength = bufferTempData.readableBytes();
                if (compressedLength < length) {
                    // specify we compressed data
                    magicByte |= compression;

                    length = compressedLength;

                    // swap buffers
                    ByteBuf tmp = bufferWithData;
                    bufferWithData = bufferTempData;
                    bufferTempData = tmp;
                }
                else {
                    // "copy" (do nothing)
                    bufferWithData.readerIndex(0); // have to reset the reader
                }
            }
            else {
                // "copy" (do nothing)
            }

            // AES CRYPTO
            if (doCrypto && connection != null) {
                if (logger2.isTraceEnabled()) {
                    logger2.trace("Encrypting data with - AES {}", connection);
                }

                Crypto.AES.encrypt(kryo.aesEngine, connection.getCryptoParameters(), bufferWithData, bufferTempData, length);

                // swap buffers
                ByteBuf tmp = bufferWithData;
                bufferWithData = bufferTempData;
                bufferTempData = tmp;
                bufferTempData.clear();

                // only needed for server UDP connections to determine if the data is encrypted or not.
                magicByte |= crypto;
            }


            // write out the "magic" byte.
            buffer.writeByte(magicByte); // leave space for the magicByte

            // transfer the tmpBuffer (if necessary) back into the "primary" buffer.
            buffer.writeBytes(bufferWithData);

        } catch (Exception ex) {
            final String msg = "Unable to serialize buffer";
            logger2.error(msg, ex);
            throw new IOException(msg, ex);
        } finally {
            // release resources
            kryo.output.setBuffer(NULL_BUFFER);

            // don't forget the clear the temp buffers!
            kryo.tmpBuffer1.clear();
            kryo.tmpBuffer2.clear();

            this.pool.release(kryo);
        }
    }

    /**
     * @param doCrypto true if crypto was used for this data.
     */
    @SuppressWarnings({"unchecked", "UnnecessaryLocalVariable"})
    private
    Object read0(final ConnectionImpl connection, final ByteBuf buffer, final int length, final boolean doCrypto) throws IOException {
        final KryoExtra kryo = (KryoExtra) this.pool.takeUninterruptibly();
        Logger logger2 = logger;

        if (kryo == null) {
            final String msg = "Unable to deserialize buffer. Kryo was null (likely interrupted during pool.take())";
            logger2.error(msg);
            return null;
        }


        int originalStartPos = 0;

        ////////////////
        // Note: we CANNOT write BACK to "buffer" since there could be additional data on it!
        ////////////////
        try {
            // read off the magic byte
            originalStartPos = buffer.readerIndex();
            byte magicByte = buffer.readByte();

            ByteBuf bufferWithData = buffer;
            ByteBuf bufferTempData = kryo.tmpBuffer1;

            // AES CRYPTO STUFF
            if (doCrypto) {
                if ((magicByte & crypto) != crypto) {
                    throw new IOException("Unable to perform crypto when data does not use crypto!");
                }

                if (logger2.isTraceEnabled()) {
                    logger2.trace("Decrypting data with - AES " + connection);
                }

                // length-1 to adjust for the magic byte
                Crypto.AES.decrypt(kryo.aesEngine, connection.getCryptoParameters(), bufferWithData, bufferTempData, length - 1);

                // correct which buffers are used
                bufferWithData = bufferTempData;
                bufferTempData = kryo.tmpBuffer2;
            }

            // did we compress it??
            //noinspection StatementWithEmptyBody
            if ((magicByte & compression) == compression) {
                if (ENABLE_SNAPPY) {
                    snappyDecompress(bufferWithData, bufferTempData, kryo.snappy);
                }
                else {
                    decompress(bufferWithData, bufferTempData, kryo.inflater);
                }

                // correct which buffers are used
                bufferWithData = bufferTempData;
            }
            else {
                // "copy" (do nothing)
            }

            // read the object from the buffer.
            kryo.input.setBuffer(bufferWithData);


            // connection will ALWAYS be of type IConnection or NULL.
            // used by RMI/some serializers to determine which connection read this object
            // NOTE: this is only valid in the context of this thread, which RMI stuff is accessed in -- so this is SAFE for RMI
            kryo.connection = connection;


            Object object = kryo.readClassAndObject(kryo.input);
            return object;
        } catch (Exception ex) {
            final String msg = "Unable to deserialize buffer";
            logger2.error(msg, ex);
            throw new IOException(msg, ex);
        } finally {
            // make sure the end of the buffer is in the correct spot.
            // move the reader index to the end of the object (since we are reading encrypted data
            // this just has to happen before the length field is reassigned.
            buffer.readerIndex(originalStartPos + length);

            // release resources
            kryo.input.setBuffer(NULL_BUFFER);

            // don't forget the clear the temp buffers!
            kryo.tmpBuffer1.clear();
            kryo.tmpBuffer2.clear();

            this.pool.release(kryo);
        }
    }

    // @formatter:off
    private enum ChunkType {
        COMPRESSED_DATA,
        UNCOMPRESSED_DATA,
        RESERVED_UNSKIPPABLE,
        RESERVED_SKIPPABLE
    }
    // @formatter:on



}
