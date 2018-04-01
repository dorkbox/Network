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
package dorkbox.network.connection.registration;

import java.util.concurrent.atomic.AtomicLong;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;

public
class MetaChannel {

    // how long between receiving data over TCP. This is used to determine how long to wait before notifying the APP,
    // so the registration message has time to arrive to the other endpoint.
    private AtomicLong nanoSecRoundTrip = new AtomicLong();

    // used to keep track and associate TCP/UDP/etc sessions. This is always defined by the server
    // a sessionId if '0', means we are still figuring it out.
    public int sessionId;

    public Channel localChannel = null; // only available for local "in jvm" channels. XOR with tcp/udp channels with CLIENT.
    public Channel tcpChannel = null;
    public Channel udpChannel = null;

    public ChannelHandler connection; // only needed until the connection has been notified.

    public ECPublicKeyParameters publicKey; // used for ECC crypto + handshake on NETWORK (remote) connections. This is the remote public key.
    public AsymmetricCipherKeyPair ecdhKey; // used for ECC Diffie-Hellman-Merkle key exchanges: see http://en.wikipedia.org/wiki/Diffie%E2%80%93Hellman_key_exchange

    // since we are using AES-GCM, the aesIV here **MUST** be exactly 12 bytes
    public byte[] aesKey;
    public byte[] aesIV;


    // indicates if the remote ECC key has changed for an IP address. If the client detects this, it will not connect.
    // If the server detects this, it has the option for additional security (two-factor auth, perhaps?)
    public boolean changedRemoteKey = false;

    public
    MetaChannel(final int sessionId) {
        this.sessionId = sessionId;
    }


    /**
     * Update the network round trip time.
     */
    public
    void updateRoundTripOnWrite() {
        this.nanoSecRoundTrip.set(System.nanoTime());
    }

    /**
     * @return the difference in time from the last write
     */
    public
    long getRoundTripTime() {
        return System.nanoTime() - this.nanoSecRoundTrip.get();
    }
}
