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
import dorkbox.network.connection.ConnectionPointWriter;
import dorkbox.network.connection.EndPoint;
import dorkbox.network.connection.ISessionManager;
import dorkbox.network.connection.UdpServer;
import dorkbox.network.connection.registration.MetaChannel;
import dorkbox.network.rmi.RmiObjectHandler;
import dorkbox.util.FastThreadLocal;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.util.NetUtil;

public
class ChannelNetworkWrapper implements ChannelWrapper {

    private final ChannelNetwork tcp;
    private final ChannelNetwork udp;

    // did the remote connection public ECC key change?
    private final boolean remotePublicKeyChanged;

    private final String remoteAddress;
    private final boolean isLoopback;
    private final EventLoop eventLoop;

    // GCM IV. hacky way to prevent tons of GC and to not clobber the original parameters
    private final byte[] aesKey; // AES-256 requires 32 bytes
    private final byte[] aesIV; // AES-GCM requires 12 bytes

    private final FastThreadLocal<ParametersWithIV> cryptoParameters;
    private final RmiObjectHandler rmiObjectHandler;

    /**
     * @param udpServer is null when created by the client, non-null when created by the server
     * @param rmiObjectHandler is a no-op handler if RMI is disabled, otherwise handles RMI object registration
     */
    public
    ChannelNetworkWrapper(MetaChannel metaChannel, UdpServer udpServer, final RmiObjectHandler rmiObjectHandler) {

        this.rmiObjectHandler = rmiObjectHandler;

        Channel tcpChannel = metaChannel.tcpChannel;
        this.eventLoop = tcpChannel.eventLoop();

        isLoopback = ((InetSocketAddress)tcpChannel.remoteAddress()).getAddress().equals(NetUtil.LOCALHOST);

        this.tcp = new ChannelNetwork(tcpChannel);

        if (metaChannel.udpChannel != null) {
            if (metaChannel.udpRemoteAddress != null) {
                this.udp = new ChannelNetworkUdp(metaChannel.udpChannel, metaChannel.udpRemoteAddress, udpServer);
            }
            else {
                this.udp = new ChannelNetwork(metaChannel.udpChannel);
            }
        }
        else {
            this.udp = null;
        }


        this.remoteAddress = ((InetSocketAddress) tcpChannel.remoteAddress()).getAddress()
                                                                             .getHostAddress();
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
    ConnectionPointWriter tcp() {
        return this.tcp;
    }

    @Override
    public
    ConnectionPointWriter udp() {
        return this.udp;
    }

    /**
     * Initialize the connection with any extra info that is needed but was unavailable at the channel construction.
     */
    @Override
    public final
    void init() {
        // nothing to do.
    }

    /**
     * Flushes the contents of the TCP/UDP/etc pipes to the actual transport.
     */
    @Override
    public
    void flush() {
        this.tcp.flush();

        if (this.udp != null) {
            this.udp.flush();
        }
    }

    @Override
    public
    EventLoop getEventLoop() {
        return this.eventLoop;
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
    void close(final ConnectionImpl connection, final ISessionManager sessionManager) {
        long maxShutdownWaitTimeInMilliSeconds = EndPoint.maxShutdownWaitTimeInMilliSeconds;

        this.tcp.close(maxShutdownWaitTimeInMilliSeconds);

        if (this.udp != null) {
            this.udp.close(maxShutdownWaitTimeInMilliSeconds);
        }

        // we need to yield the thread here, so that the socket has a chance to close
        Thread.yield();
    }

    @Override
    public
    int id() {
        return this.tcp.id();
    }

    @Override
    public
    int hashCode() {
        return this.remoteAddress.hashCode();
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
