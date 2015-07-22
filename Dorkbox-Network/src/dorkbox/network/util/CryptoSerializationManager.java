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
package dorkbox.network.util;

import dorkbox.network.connection.ConnectionImpl;
import dorkbox.util.SerializationManager;
import io.netty.buffer.ByteBuf;

/**
 * Threads reading/writing, it messes up a single instance.
 * it is possible to use a single kryo with the use of synchronize, however - that defeats the point of multi-threaded
 */
public
interface CryptoSerializationManager extends SerializationManager, RMISerializationManager {

    /**
     * Determines if this buffer is encrypted or not.
     */
    boolean isEncrypted(ByteBuf buffer);


    /**
     * Waits until a kryo is available to write, using CAS operations to prevent having to synchronize.
     * <p/>
     * There is a small speed penalty if there were no kryo's available to use.
     */
    void writeWithCryptoTcp(ConnectionImpl connection, ByteBuf buffer, Object message);

    /**
     * Waits until a kryo is available to write, using CAS operations to prevent having to synchronize.
     * <p/>
     * There is a small speed penalty if there were no kryo's available to use.
     */
    void writeWithCryptoUdp(ConnectionImpl connection, ByteBuf buffer, Object message);

    /**
     * Reads an object from the buffer.
     * <p/>
     * Crypto + sequence number
     *
     * @param connection can be NULL
     * @param length     should ALWAYS be the length of the expected object!
     */
    Object readWithCryptoTcp(ConnectionImpl connection, ByteBuf buffer, int length);

    /**
     * Reads an object from the buffer.
     * <p/>
     * Crypto + sequence number
     *
     * @param connection can be NULL
     * @param length     should ALWAYS be the length of the expected object!
     */
    Object readWithCryptoUdp(ConnectionImpl connection, ByteBuf buffer, int length);
}
