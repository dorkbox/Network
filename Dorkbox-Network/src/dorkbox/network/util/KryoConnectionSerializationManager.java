package dorkbox.network.util;

import com.esotericsoftware.kryo.*;
import com.esotericsoftware.kryo.factories.ReflectionSerializerFactory;
import com.esotericsoftware.kryo.factories.SerializerFactory;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.pool.KryoFactory;
import com.esotericsoftware.kryo.pool.KryoPool;
import com.esotericsoftware.kryo.serializers.FieldSerializer;
import com.esotericsoftware.kryo.util.MapReferenceResolver;
import dorkbox.network.connection.Connection;
import dorkbox.network.pipeline.ByteBufInput;
import dorkbox.network.pipeline.ByteBufOutput;
import dorkbox.network.rmi.RmiRegisterClassesCallback;
import dorkbox.network.rmi.SerializerRegistration;
import dorkbox.network.util.exceptions.NetException;
import dorkbox.network.util.serializers.FieldAnnotationAwareSerializer;
import dorkbox.network.util.serializers.IgnoreSerialization;
import dorkbox.util.crypto.Crypto;
import dorkbox.util.crypto.bouncycastle.GCMBlockCipher_ByteBuf;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.compression.CompressionException;
import io.netty.handler.codec.compression.SnappyAccess;
import org.bouncycastle.crypto.engines.AESFastEngine;
import org.jctools.queues.MpmcArrayQueue;
import org.slf4j.Logger;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Threads reading/writing, it messes up a single instance.
 * it is possible to use a single kryo with the use of synchronize, however - that defeats the point of multi-threaded
 */
public
class KryoConnectionSerializationManager implements ConnectionSerializationManager {

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(KryoConnectionSerializationManager.class);
    private static final boolean ENABLE_SNAPPY = false;

    /**
     * Specify if we want KRYO to use unsafe memory for serialization, or to use the ASM backend. Unsafe memory use is WAY faster, but is
     * limited to the "same endianess" on all endpoints, and unsafe DOES NOT work on android.
     */
    public static boolean useUnsafeMemory = false;

    /**
     * The minimum amount that we'll consider actually attempting to compress.
     * This value is preamble + the minimum length our Snappy service will
     * compress (instead of just emitting a literal).
     */
    private static final int MIN_COMPRESSIBLE_LENGTH = 18;


    private final KryoPool pool;
    private RmiRegisterClassesCallback rmiCallback;

    public static
    KryoConnectionSerializationManager DEFAULT() {
        return DEFAULT(false, true);
    }

    public static
    KryoConnectionSerializationManager DEFAULT(final boolean references, final boolean registrationRequired) {
        // ignore fields that have the "IgnoreSerialization" annotation.
        Set<Class<? extends Annotation>> marks = new HashSet<Class<? extends Annotation>>();
        marks.add(IgnoreSerialization.class);
        SerializerFactory disregardingFactory = new FieldAnnotationAwareSerializer.Factory(marks, true);

        // from the list-serve email. This offers 8x performance in resolving references over the default impl.
        final BinaryListReferenceResolver resolver = new BinaryListReferenceResolver();
        return new KryoConnectionSerializationManager(references, registrationRequired, resolver, disregardingFactory);
    }


    // @formatter:off
    private enum ChunkType {
        COMPRESSED_DATA,
        UNCOMPRESSED_DATA,
        RESERVED_UNSKIPPABLE,
        RESERVED_SKIPPABLE
    }

    /** bit masks */
    private static final int compression = 1 << 0;
    private static final int crypto      = 1 << 1;
   // @formatter:on

    // compression options
    private static final int compressionLevel = 6;


    @SuppressWarnings("rawtypes")
    class KryoRegister {
        public Class<?> clazz = null;
        public Serializer<?> serializer = null;
        public Integer id = null;
        public SerializerRegistration registration;

        public
        KryoRegister() {
        }
    }


    class KryoExtra extends Kryo {
        private final ByteBufOutput outputBuffer;
        private final ByteBufInput inputBuffer;

        private final Inflater inflater;
        private final Deflater deflater;

        private final SnappyAccess snappy;

        private final ByteBuf tmpBuffer1;
        private final ByteBuf tmpBuffer2;

        private final GCMBlockCipher_ByteBuf aesEngine;


        public
        KryoExtra() {
            this.snappy = new SnappyAccess();
            this.deflater = new Deflater(compressionLevel, true);
            this.inflater = new Inflater(true);

            this.inputBuffer = new ByteBufInput();
            this.outputBuffer = new ByteBufOutput();

            this.tmpBuffer1 = Unpooled.buffer(1024);
            this.tmpBuffer2 = Unpooled.buffer(1024);
            this.aesEngine = new GCMBlockCipher_ByteBuf(new AESFastEngine());
        }
    }



    final ArrayList<KryoRegister> registers = new ArrayList<KryoRegister>(16);


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
     * @param referenceResolver    Sets the reference resolver and enables references.
     *                             <p/>
     * @param factory              Sets the serializer factory to use when no {@link Kryo#addDefaultSerializer(Class, Class) default
     *                             serializers} match an object's type. Default is {@link ReflectionSerializerFactory} with
     *                             {@link FieldSerializer}. @see Kryo#newDefaultSerializer(Class)
     *                             <p/>
     */
    public
    KryoConnectionSerializationManager(final boolean references,
                                       final boolean registrationRequired,
                                       final ReferenceResolver referenceResolver,
                                       final SerializerFactory factory) {
        KryoFactory kryoFactory = new KryoFactory() {
            @SuppressWarnings("unchecked")
            @Override
            public
            KryoExtra create() {
                KryoExtra kryo = new KryoExtra();

                // we HAVE to pre-allocate the KRYOs
                boolean useAsm = !useUnsafeMemory;

                kryo.setAsmEnabled(useAsm);
                kryo.setRegistrationRequired(registrationRequired);

                kryo.setReferences(references);

                if (referenceResolver != null) {
                    kryo.setReferenceResolver(referenceResolver);
                }
                if (factory != null) {
                    kryo.setDefaultSerializer(factory);
                }

                for (KryoRegister register : KryoConnectionSerializationManager.this.registers) {
                    if (register.registration != null) {
                        Registration reg = kryo.register(register.clazz);
                        register.registration.register(reg.getSerializer());
                    }
                    else {
                        if (register.serializer != null && register.id != null) {
                            kryo.register(register.clazz, register.serializer, register.id);
                        }
                        else if (register.serializer != null) {
                            kryo.register(register.clazz, register.serializer);
                        }
                        else {
                            kryo.register(register.clazz);
                        }
                    }
                }

                if (KryoConnectionSerializationManager.this.rmiCallback != null) {
                    // necessary for the RMI bridge. Only called once, but necessary for all kryo instances
                    KryoConnectionSerializationManager.this.rmiCallback.registerForClasses(kryo);
                }

                return kryo;
            }
        };

        this.pool = new KryoPool.Builder(kryoFactory).queue(new MpmcArrayQueue<Kryo>(Runtime.getRuntime()
                                                                                       .availableProcessors() * 32))
                                                .build();
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
    public
    void register(Class<?> clazz) {
        KryoRegister kryoRegister = new KryoRegister();
        kryoRegister.clazz = clazz;
        this.registers.add(kryoRegister);
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
    public
    void register(Class<?> clazz, Serializer<?> serializer) {
        KryoRegister kryoRegister = new KryoRegister();
        kryoRegister.clazz = clazz;
        kryoRegister.serializer = serializer;
        this.registers.add(kryoRegister);
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
    public
    void register(Class<?> clazz, Serializer<?> serializer, int id) {
        KryoRegister kryoRegister = new KryoRegister();
        kryoRegister.clazz = clazz;
        kryoRegister.serializer = serializer;
        kryoRegister.id = id;
        this.registers.add(kryoRegister);
    }

    /**
     * <b>primarily used by RMI</b> It is not common to call this method!
     * <p/>
     * Registers the class using the lowest, next available integer ID and the
     * {@link SerializerRegistration(Class) serializer}. If the class
     * is already registered, the existing entry is updated with the new
     * serializer. Registering a primitive also affects the corresponding
     * primitive wrapper.
     * <p/>
     * Because the ID assigned is affected by the IDs registered before it, the
     * order classes are registered is important when using this method. The
     * order must be the same at deserialization as it was for serialization.
     */
    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public
    void registerSerializer(Class<?> clazz, SerializerRegistration registration) {
        KryoRegister kryoRegister = new KryoRegister();
        kryoRegister.clazz = clazz;
        kryoRegister.registration = registration;
        this.registers.add(kryoRegister);
    }

    /**
     * Necessary to register classes for RMI, only called once when the RMI bridge is created.
     */
    @Override
    public
    void registerForRmiClasses(RmiRegisterClassesCallback callback) {
        this.rmiCallback = callback;
    }

    /**
     * If the class is not registered and {@link Kryo#setRegistrationRequired(boolean)} is false, it is
     * automatically registered using the {@link Kryo#addDefaultSerializer(Class, Class) default serializer}.
     *
     * @throws IllegalArgumentException if the class is not registered and {@link Kryo#setRegistrationRequired(boolean)} is true.
     * @see ClassResolver#getRegistration(Class)
     */
    @Override
    public
    Registration getRegistration(Class<?> clazz) {
        // registration is always required, will throw exception if this class is not already registered
        final Kryo kryo = this.pool.borrow();
        Registration r;

        try {
            r = kryo.getRegistration(clazz);
        } finally {
            this.pool.release(kryo);
        }

        return r;
    }

    /**
     * Determines if this buffer is encrypted or not.
     */
    @Override
    public final
    boolean isEncrypted(ByteBuf buffer) {
        // read off the magic byte
        byte magicByte = buffer.getByte(buffer.readerIndex());
        return (magicByte & crypto) == crypto;
    }

    /**
     * Waits until a kryo is available to write, using CAS operations to prevent having to synchronize.
     * <p/>
     * No crypto and no sqeuence number
     * <p/>
     * There is a small speed penalty if there were no kryo's available to use.
     */
    @Override
    public final
    void write(ByteBuf buffer, Object message) {
        write0(null, buffer, message, false);
    }

    /**
     * Writes the class and object using an available kryo instance
     */
    @Override
    public
    void writeFullClassAndObject(Output output, Object value) {
        final Kryo kryo = this.pool.borrow();
        boolean prev = kryo.isRegistrationRequired();
        kryo.setRegistrationRequired(false);

        try {
            kryo.writeClassAndObject(output, value);
        } catch (KryoException ex) {
            throw new NetException("Unable to serialize buffer", ex);
        } finally {
            kryo.setRegistrationRequired(prev);
            this.pool.release(kryo);
        }
    }

    @Override
    public
    Object readFullClassAndObject(final Input input) {
        final Kryo kryo = this.pool.borrow();
        boolean prev = kryo.isRegistrationRequired();
        kryo.setRegistrationRequired(false);

        try {
            Object readClassAndObject = kryo.readClassAndObject(input);
            return readClassAndObject;
        } catch (KryoException ex) {
            throw new NetException("Unable to deserialize buffer", ex);
        } finally {
            kryo.setRegistrationRequired(prev);
            this.pool.release(kryo);
        }
    }

    @Override
    public
    Kryo borrow() {
        return this.pool.borrow();
    }

    @Override
    public
    void release(final Kryo kryo) {
        this.pool.release(kryo);
    }

    /**
     * Waits until a kryo is available to write, using CAS operations to prevent having to synchronize.
     * <p/>
     * There is a small speed penalty if there were no kryo's available to use.
     */
    @Override
    public final
    void writeWithCryptoTcp(Connection connection, ByteBuf buffer, Object message) {
        if (connection == null) {
            throw new NetException("Unable to perform crypto when NO network connection!");
        }

        write0(connection, buffer, message, true);
    }

    /**
     * Waits until a kryo is available to write, using CAS operations to prevent having to synchronize.
     * <p/>
     * There is a small speed penalty if there were no kryo's available to use.
     */
    @Override
    public final
    void writeWithCryptoUdp(Connection connection, ByteBuf buffer, Object message) {
        if (connection == null) {
            throw new NetException("Unable to perform crypto when NO network connection!");
        }

        write0(connection, buffer, message, true);
    }


    /**
     * @param doCrypto true if we want to perform crypto on this data.
     */
    @SuppressWarnings("unchecked")
    private
    void write0(Connection connection, ByteBuf buffer, Object message, boolean doCrypto) {
        final KryoExtra kryo = (KryoExtra) this.pool.borrow();
        Logger logger2 = logger;

        try {
            byte magicByte = (byte) 0x00000000;

            ByteBuf bufferWithData = kryo.tmpBuffer1;
            ByteBuf bufferTempData = kryo.tmpBuffer2;

            // write the object to the TEMP buffer! this will be compressed with snappy
            kryo.outputBuffer.setBuffer(bufferWithData);

            // connection will ALWAYS be of type Connection or NULL.
            // used by RMI/some serializers to determine which connection wrote this object
            // NOTE: this is only valid in the context of this thread, which RMI stuff is accessed in -- so this is SAFE for RMI
            if (connection != null) {
                kryo.getContext()
                    .put(Connection.connection, connection);
            }

            kryo.writeClassAndObject(kryo.outputBuffer, message);

            // release resources
            kryo.outputBuffer.setBuffer((ByteBuf) null);

            // save off how much data the object took + the length of the (possible) sequence.
            int length = bufferWithData.writerIndex();  // it started at ZERO (since it's written to the temp buffer.

            // snappy compression
            // tmpBuffer2 = compress(tmpBuffer1)
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
                    magicByte = (byte) (magicByte | compression);

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

            // at this point, we have 2 options for *bufferWithData*
            // compress -> tmpBuffers2 has data
            // copy     -> tmpBuffers1 has data


            // AES CRYPTO
            if (doCrypto && connection != null) {
                if (logger2.isTraceEnabled()) {
                    logger2.trace("Encrypting data with - AES {}", connection);
                }

                length = Crypto.AES.encrypt(kryo.aesEngine, connection.getCryptoParameters(), bufferWithData, bufferTempData, length);

                // swap buffers
                ByteBuf tmp = bufferWithData;
                bufferWithData = bufferTempData;
                bufferTempData = tmp;
                bufferTempData.clear();

                // only needed for server UDP connections to determine if the data is encrypted or not.
                magicByte = (byte) (magicByte | crypto);
            }


            /// MOVE EVERYTHING TO THE PROPER BYTE BUF

            // write out the "magic" byte.
            buffer.writeByte(magicByte); // leave space for the magic magicByte

            // transfer the tmpBuffer (if necessary) back into the "primary" buffer.
            buffer.writeBytes(bufferWithData);

            // don't forget the clear the temp buffers!
            kryo.tmpBuffer1.clear();
            kryo.tmpBuffer2.clear();


        } catch (KryoException ex) {
            throw new NetException("Unable to serialize buffer", ex);
        } finally {
            this.pool.release(kryo);
        }
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
    Object read(ByteBuf buffer, int length) {
        return read0(null, buffer, length, false);
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
    Object readWithCryptoTcp(Connection connection, ByteBuf buffer, int length) {
        if (connection == null) {
            throw new NetException("Unable to perform crypto when NO network connection!");
        }

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
    Object readWithCryptoUdp(Connection connection, ByteBuf buffer, int length) {
        if (connection == null) {
            throw new NetException("Unable to perform crypto when NO network connection!");
        }

        return read0(connection, buffer, length, true);
    }

    /**
     * @param doCrypto true if crypto was used for this data.
     */
    @SuppressWarnings("unchecked")
    private
    Object read0(Connection connection, ByteBuf buffer, int length, boolean doCrypto) {
        final KryoExtra kryo = (KryoExtra) this.pool.borrow();
        Logger logger2 = logger;

        int originalLength = 0;
        int originalStartPos = 0;

        ////////////////
        // Note: we CANNOT write BACK to "buffer" since there could be additional data on it!
        ////////////////
        try {
            // read off the magic byte
            int startPosition = buffer.readerIndex();
            byte magicByte = buffer.readByte();

            // adjust for the magic byte
            startPosition++;
            length--;

            originalLength = length;
            originalStartPos = startPosition;

            ByteBuf bufferWithData = buffer;
            ByteBuf bufferTempData = kryo.tmpBuffer2;

            // AES CRYPTO STUFF
            if (doCrypto) {
                if ((magicByte & crypto) != crypto) {
                    throw new NetException("Unable to perform crypto when data does not to use crypto!");
                }

                if (logger2.isTraceEnabled()) {
                    logger2.trace("Decrypting data with - AES " + connection);
                }

                Crypto.AES.decrypt(kryo.aesEngine, connection.getCryptoParameters(), bufferWithData, bufferTempData, length);

                // since we "nuked" the start position, we have to make sure the compressor picks up the change.
                startPosition = 0;

                // swap buffers
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

                // swap buffers
                ByteBuf tmp = bufferWithData;
                bufferWithData = bufferTempData;
                bufferTempData = tmp;
            }
            else {
                // "copy" (do nothing)
            }

            // read the object from the buffer.
            kryo.inputBuffer.setBuffer(bufferWithData);



            // connection will ALWAYS be of type IConnection or NULL.
            // used by RMI/some serializers to determine which connection read this object
            // NOTE: this is only valid in the context of this thread, which RMI stuff is accessed in -- so this is SAFE for RMI
            if (connection != null) {
                kryo.getContext()
                    .put(Connection.connection, connection);
            }

            Object readClassAndObject = kryo.readClassAndObject(kryo.inputBuffer);

            return readClassAndObject;
        } catch (KryoException ex) {
            throw new NetException("Unable to deserialize buffer", ex);
        } finally {
            // release resources
            kryo.inputBuffer.setBuffer((ByteBuf) null);

            // make sure the end of the buffer is in the correct spot.
            // move the reader index to the end of the object (since we are reading encrypted data
            // this just has to happen before the length field is reassigned.
            buffer.readerIndex(originalStartPos + originalLength);

            // don't forget the clear the temp buffers!
            kryo.tmpBuffer1.clear();
            kryo.tmpBuffer2.clear();

            this.pool.release(kryo);
        }
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
    void decompress(ByteBuf inputBuffer, ByteBuf outputBuffer, Inflater decompress) {
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
                throw new NetException(e.getCause());
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
    void snappyDecompress(ByteBuf inputBuffer, ByteBuf outputBuffer, SnappyAccess snappy) {
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
            throw new NetException("Unable to decompress SNAPPY data!! " + e.getMessage());
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
}
