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

import org.bouncycastle.crypto.params.ParametersWithIV;

import dorkbox.network.rmi.ConnectionRmiSupport;

/**
 * Supporting methods that are internal to the network stack
 */
public
interface Connection_ extends Connection {

    /**
     * @return the RMI support for this connection
     */
    ConnectionRmiSupport rmiSupport();

    /**
     * This is the per-message sequence number.
     *
     *  The IV for AES-GCM must be 12 bytes, since it's 4 (salt) + 8 (external counter) + 4 (GCM counter)
     *  The 12 bytes IV is created during connection registration, and during the AES-GCM crypto, we override the last 8 with this
     *  counter, which is also transmitted as an optimized int. (which is why it starts at 0, so the transmitted bytes are small)
     */
    long getNextGcmSequence();


    /**
     * @return a threadlocal AES key + IV. key=32 byte, iv=12 bytes (AES-GCM implementation). This is a threadlocal
     *          because multiple protocols can be performing crypto AT THE SAME TIME, and so we have to make sure that operations don't
     *          clobber each other
     */
    ParametersWithIV getCryptoParameters();

    /**
     * @return the endpoint associated with this connection
     */
    @SuppressWarnings("rawtypes")
    EndPoint getEndPoint();
}
