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

import dorkbox.network.connection.UdpServer;
import io.netty.channel.Channel;

import java.net.InetSocketAddress;

public
class ChannelNetworkUdp extends ChannelNetwork {

    private final InetSocketAddress udpRemoteAddress;
    private final UdpServer udpServer;

    public
    ChannelNetworkUdp(Channel channel, InetSocketAddress udpRemoteAddress, UdpServer udpServer) {
        super(channel);

        this.udpRemoteAddress = udpRemoteAddress;
        this.udpServer = udpServer; // ONLY valid in the server!
    }

    @Override
    public
    void write(Object object) {
        // this shoots out the SERVER pipeline, which is SLIGHTLY different!
        super.write(new UdpWrapper(object, udpRemoteAddress));
    }

    @Override
    public
    void close(long maxShutdownWaitTimeInMilliSeconds) {
        // we ONLY want to close the UDP channel when we are STOPPING the server, otherwise we close the UDP channel
        // that listens for new connections!   SEE Server.close().
        // super.close(maxShutdownWaitTimeInMilliSeconds);

        // need to UNREGISTER the address from my ChannelManager.
        if (udpServer != null) {
            // only the server does this.
            udpServer.unRegisterServerUDP(udpRemoteAddress);
        }
    }
}
