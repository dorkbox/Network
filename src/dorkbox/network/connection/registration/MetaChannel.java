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

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.SecretKey;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;

import dorkbox.network.connection.ConnectionImpl;
import io.netty.channel.Channel;

public
class MetaChannel {

    // used to keep track and associate TCP/UDP/etc sessions. This is always defined by the server
    // a sessionId if '0', means we are still figuring it out.
    public volatile int sessionId;

    public volatile Channel localChannel = null; // only available for local "in jvm" channels. XOR with tcp/udp channels with CLIENT, server can have all types at once
    public volatile Channel tcpChannel = null;
    public volatile Channel udpChannel = null;

    // keep track of how many protocols to register, so that way when we are ready to connect the SERVER sends a message to the client over
    // all registered protocols and the last protocol to receive the message does the registration
    public AtomicInteger totalProtocols = new AtomicInteger(0);


    // only permits the FIST protocol for running the pipeline upgrade.
    public AtomicBoolean canUpgradePipeline = new AtomicBoolean(true);

    public volatile ConnectionImpl connection; // only needed until the connection has been notified.

    public volatile ECPublicKeyParameters publicKey; // used for ECC crypto + handshake on NETWORK (remote) connections. This is the remote public key.
    public volatile AsymmetricCipherKeyPair ecdhKey; // used for ECC Diffie-Hellman-Merkle key exchanges: see http://en.wikipedia.org/wiki/Diffie%E2%80%93Hellman_key_exchange

    public volatile SecretKey secretKey;

    // indicates if the remote ECC key has changed for an IP address. If the client detects this, it will not connect.
    // If the server detects this, it has the option for additional security (two-factor auth, perhaps?)
    public volatile boolean changedRemoteKey = false;

    public volatile byte remainingFragments;
    public volatile byte[] fragmentedRegistrationDetails;


    public
    MetaChannel(final int sessionId) {
        this.sessionId = sessionId;
    }
}
