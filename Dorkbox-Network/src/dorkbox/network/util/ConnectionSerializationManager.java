package dorkbox.network.util;

import dorkbox.network.connection.Connection;
import dorkbox.util.SerializationManager;
import io.netty.buffer.ByteBuf;


/**
 * Threads reading/writing, it messes up a single instance.
 * it is possible to use a single kryo with the use of synchronize, however - that defeats the point of multi-threaded
 */
public
interface ConnectionSerializationManager extends SerializationManager, RMISerializationManager {

    /**
     * Determines if this buffer is encrypted or not.
     */
    boolean isEncrypted(ByteBuf buffer);


    /**
     * Waits until a kryo is available to write, using CAS operations to prevent having to synchronize.
     * <p/>
     * There is a small speed penalty if there were no kryo's available to use.
     */
    void writeWithCryptoTcp(Connection connection, ByteBuf buffer, Object message);

    /**
     * Waits until a kryo is available to write, using CAS operations to prevent having to synchronize.
     * <p/>
     * There is a small speed penalty if there were no kryo's available to use.
     */
    void writeWithCryptoUdp(Connection connection, ByteBuf buffer, Object message);



    /**
     * Reads an object from the buffer.
     * <p/>
     * Crypto + sequence number
     *
     * @param connection can be NULL
     * @param length     should ALWAYS be the length of the expected object!
     */
    Object readWithCryptoTcp(Connection connection, ByteBuf buffer, int length);

    /**
     * Reads an object from the buffer.
     * <p/>
     * Crypto + sequence number
     *
     * @param connection can be NULL
     * @param length     should ALWAYS be the length of the expected object!
     */
    Object readWithCryptoUdp(Connection connection, ByteBuf buffer, int length);
}
