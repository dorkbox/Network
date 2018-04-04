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
package dorkbox.network.connection.wrapper;

import java.net.InetSocketAddress;

import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;

import dorkbox.network.connection.ConnectionImpl;
import dorkbox.network.connection.ConnectionPoint;
import dorkbox.network.connection.EndPoint;
import dorkbox.network.connection.ISessionManager;
import dorkbox.network.connection.registration.MetaChannel;
import dorkbox.network.rmi.RmiObjectHandler;
import dorkbox.util.FastThreadLocal;
import io.netty.bootstrap.DatagramCloseMessage;
import io.netty.util.NetUtil;

public
class ChannelNetworkWrapper implements ChannelWrapper {

    private final int sessionId;

    private final ChannelNetwork tcp;
    private final ChannelNetwork udp;

    // did the remote connection public ECC key change?
    private final boolean remotePublicKeyChanged;

    private final String remoteAddress;
    private final boolean isLoopback;

    // GCM IV. hacky way to prevent tons of GC and to not clobber the original parameters
    private final byte[] aesKey; // AES-256 requires 32 bytes
    private final byte[] aesIV; // AES-GCM requires 12 bytes

    private final FastThreadLocal<ParametersWithIV> cryptoParameters;
    private final RmiObjectHandler rmiObjectHandler;

    /**
     * @param rmiObjectHandler is a no-op handler if RMI is disabled, otherwise handles RMI object registration
     */
    public
    ChannelNetworkWrapper(final MetaChannel metaChannel, final InetSocketAddress remoteAddress, final RmiObjectHandler rmiObjectHandler) {

        this.sessionId = metaChannel.sessionId;
        this.rmiObjectHandler = rmiObjectHandler;
        this.isLoopback = remoteAddress.getAddress().equals(NetUtil.LOCALHOST);

        if (metaChannel.tcpChannel != null) {
            this.tcp = new ChannelNetwork(metaChannel.tcpChannel);
        } else {
            this.tcp = null;
        }

        if (metaChannel.udpChannel != null) {
            this.udp = new ChannelNetwork(metaChannel.udpChannel);
        }
        else {
            this.udp = null;
        }


        this.remoteAddress = remoteAddress.getAddress().getHostAddress();
        this.remotePublicKeyChanged = metaChannel.changedRemoteKey;

        // AES key & IV (only for networked connections)
        aesKey = metaChannel.aesKey;
        aesIV = metaChannel.aesIV;

        cryptoParameters = new FastThreadLocal<ParametersWithIV>() {
            @Override
            public
            ParametersWithIV initialValue() {
                return new ParametersWithIV(new KeyParameter(aesKey), aesIV);
            }
        };
    }

    public final
    boolean remoteKeyChanged() {
        return this.remotePublicKeyChanged;
    }

    @Override
    public
    ConnectionPoint tcp() {
        return this.tcp;
    }

    @Override
    public
    ConnectionPoint udp() {
        return this.udp;
    }

    /**
     * Flushes the contents of the TCP/UDP/etc pipes to the actual transport.
     */
    @Override
    public
    void flush() {
        if (this.tcp != null) {
            this.tcp.flush();
        }

        if (this.udp != null) {
            this.udp.flush();
        }
    }

    /**
     * @return a threadlocal AES key + IV. key=32 byte, iv=12 bytes (AES-GCM implementation). This is a threadlocal
     *          because multiple protocols can be performing crypto AT THE SAME TIME, and so we have to make sure that operations don't
     *          clobber each other
     */
    @Override
    public
    ParametersWithIV cryptoParameters() {
        return this.cryptoParameters.get();
    }

    @Override
    public
    boolean isLoopback() {
        return isLoopback;
    }

    @Override
    public
    RmiObjectHandler manageRmi() {
        return rmiObjectHandler;
    }

    @Override
    public
    String getRemoteHost() {
        return this.remoteAddress;
    }

    @Override
    public
    void close(final ConnectionImpl connection, final ISessionManager sessionManager, boolean hintedClose) {
        long maxShutdownWaitTimeInMilliSeconds = EndPoint.maxShutdownWaitTimeInMilliSeconds;

        if (this.tcp != null) {
            this.tcp.close(0, maxShutdownWaitTimeInMilliSeconds);
        }

        if (this.udp != null) {
            if (hintedClose) {
                // we already hinted that we should close this channel... don't do it again!
                this.udp.close(0, maxShutdownWaitTimeInMilliSeconds);
            }
            else {
                // send a hint to the other connection that we should close. While not always 100% successful, this helps clean up connections
                // on the remote end
                try {
                    this.udp.write(new DatagramCloseMessage());
                    this.udp.flush();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                this.udp.close(200, maxShutdownWaitTimeInMilliSeconds);
            }
        }

        // we need to yield the thread here, so that the socket has a chance to close
        Thread.yield();
    }

    @Override
    public
    int id() {
        return this.sessionId;
    }

    @Override
    public
    int hashCode() {
        // a unique ID for every connection. However, these ID's can also be reused
        return this.sessionId;
    }

    @Override
    public
    boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!super.equals(obj)) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }

        ChannelNetworkWrapper other = (ChannelNetworkWrapper) obj;
        if (this.remoteAddress == null) {
            if (other.remoteAddress != null) {
                return false;
            }
        }
        else if (!this.remoteAddress.equals(other.remoteAddress)) {
            return false;
        }
        return true;
    }

    @Override
    public
    String toString() {
        return "NetworkConnection [" + getRemoteHost() + "]";
    }
}
