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

import java.util.concurrent.atomic.AtomicBoolean;

import org.bouncycastle.crypto.params.ParametersWithIV;

import dorkbox.network.connection.ConnectionImpl;
import dorkbox.network.connection.ConnectionPointWriter;
import dorkbox.network.connection.EndPoint;
import dorkbox.network.connection.ISessionManager;
import dorkbox.network.connection.registration.MetaChannel;
import dorkbox.network.rmi.RmiObjectHandler;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.channel.local.LocalAddress;

public
class ChannelLocalWrapper implements ChannelWrapper, ConnectionPointWriter {

    private final Channel channel;
    private final RmiObjectHandler rmiObjectHandler;

    private final AtomicBoolean shouldFlush = new AtomicBoolean(false);
    private String remoteAddress;

    public
    ChannelLocalWrapper(MetaChannel metaChannel, final RmiObjectHandler rmiObjectHandler) {
        this.channel = metaChannel.localChannel;
        this.rmiObjectHandler = rmiObjectHandler;
    }

    /**
     * Write an object to the underlying channel
     */
    @Override
    public
    void write(Object object) {
        this.channel.write(object);
        this.shouldFlush.set(true);
    }

    /**
     * @return true if the channel is writable. Useful when sending large amounts of data at once.
     */
    @Override
    public
    boolean isWritable() {
        // it's immediate, since it's in the same JVM.
        return true;
    }

    @Override
    public
    ConnectionPointWriter tcp() {
        return this;
    }

    @Override
    public
    ConnectionPointWriter udp() {
        return this;
    }

    /**
     * Initialize the connection with any extra info that is needed but was unavailable at the channel construction.
     */
    @Override
    public final
    void init() {
        this.remoteAddress = ((LocalAddress) this.channel.remoteAddress()).id();
    }

    /**
     * Flushes the contents of the LOCAL pipes to the actual transport.
     */
    @Override
    public
    void flush() {
        if (this.shouldFlush.compareAndSet(true, false)) {
            this.channel.flush();
        }
    }

    @Override
    public
    EventLoop getEventLoop() {
        return this.channel.eventLoop();
    }

    @Override
    public
    ParametersWithIV cryptoParameters() {
        return null;
    }

    @Override
    public
    boolean isLoopback() {
        return true;
    }

    @Override
    public
    RmiObjectHandler manageRmi() {
        return rmiObjectHandler;
    }

    @Override
    public final
    String getRemoteHost() {
        return this.remoteAddress;
    }

    @Override
    public
    void close(ConnectionImpl connection, ISessionManager sessionManager) {
        long maxShutdownWaitTimeInMilliSeconds = EndPoint.maxShutdownWaitTimeInMilliSeconds;

        this.shouldFlush.set(false);

        // Wait until the connection is closed or the connection attempt fails.
        this.channel.close()
                    .awaitUninterruptibly(maxShutdownWaitTimeInMilliSeconds);
    }

    @Override
    public
    int id() {
        return this.channel.hashCode();
    }

    @Override
    public
    int hashCode() {
        return this.channel.hashCode();
    }

    @Override
    public
    boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        ChannelLocalWrapper other = (ChannelLocalWrapper) obj;
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
}
