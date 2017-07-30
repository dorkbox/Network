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

import java.net.InetSocketAddress;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;

import dorkbox.network.connection.ConnectionImpl;
import io.netty.channel.Channel;

// @formatter:off
public
class MetaChannel {

    // how long between receiving data over TCP. This is used to determine how long to wait before notifying the APP,
    // so the registration message has time to arrive to the other endpoint.
    private volatile long nanoSecBetweenTCP = 0L;


    public Integer connectionID = null; // only used during the registration process
    public Channel localChannel = null; // only available for local "in jvm" channels. XOR with tcp/udp channels with CLIENT.
    public Channel tcpChannel   = null;

    // channel here (on server or socket.bind connections) doesn't have the remote address available.
    // It is apart of the inbound message, however.
    // ALSO not necessary to close it, since the server handles that.
    public Channel udpChannel   = null;
    public InetSocketAddress udpRemoteAddress = null; // SERVER ONLY. needed to be aware of the remote address to send UDP replies to

    public Channel udtChannel   = null;

    public ConnectionImpl connection; // only needed until the connection has been notified.

    public ECPublicKeyParameters publicKey; // used for ECC crypto + handshake on NETWORK (remote) connections. This is the remote public key.

    public AsymmetricCipherKeyPair ecdhKey; // used for ECC Diffie-Hellman-Merkle key exchanges: see http://en.wikipedia.org/wiki/Diffie%E2%80%93Hellman_key_exchange

    // since we are using AES-GCM, the aesIV here **MUST** be exactly 12 bytes
    public byte[] aesKey;
    public byte[] aesIV;


    // indicates if the remote ECC key has changed for an IP address. If the client detects this, it will not connect.
    // If the server detects this, it has the option for additional security (two-factor auth, perhaps?)
    public boolean changedRemoteKey = false;

    public
    void close() {
        if (this.localChannel != null) {
            this.localChannel.close();
        }

        if (this.tcpChannel != null) {
            this.tcpChannel.close();
        }

        if (this.udtChannel != null) {
            this.udtChannel.close();
        }

        // only the CLIENT will have this.
        if (this.udpChannel != null && this.udpRemoteAddress == null) {
            this.udpChannel.close();
        }
    }

    public
    void close(final long maxShutdownWaitTimeInMilliSeconds) {
        if (this.localChannel != null && this.localChannel.isOpen()) {
            this.localChannel.close();
        }

        if (this.tcpChannel != null && this.tcpChannel.isOpen()) {
            this.tcpChannel.close()
                           .awaitUninterruptibly(maxShutdownWaitTimeInMilliSeconds);
        }

        if (this.udtChannel != null && this.udtChannel.isOpen()) {
            this.udtChannel.close()
                           .awaitUninterruptibly(maxShutdownWaitTimeInMilliSeconds);
        }

        // only the CLIENT will have this.
        if (this.udpChannel != null && this.udpRemoteAddress == null && this.udpChannel.isOpen()) {
            this.udpChannel.close()
                           .awaitUninterruptibly(maxShutdownWaitTimeInMilliSeconds);
        }
    }

    /**
     * Update the TCP round trip time. Make sure to REFRESH this every time you SEND TCP data!!
     */
    public
    void updateTcpRoundTripTime() {
        this.nanoSecBetweenTCP = System.nanoTime() - this.nanoSecBetweenTCP;
    }

    public
    long getNanoSecBetweenTCP() {
        return this.nanoSecBetweenTCP;
    }
}
