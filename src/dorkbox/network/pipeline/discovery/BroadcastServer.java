/*
 * Copyright 2018 dorkbox, llc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package dorkbox.network.pipeline.discovery;

import java.net.InetSocketAddress;

import dorkbox.network.Broadcast;
import dorkbox.network.Server;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.socket.DatagramPacket;

/**
 * Manages the response to broadcast events
 */
public
class BroadcastServer {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(Server.class.getSimpleName());

    public
    BroadcastServer() {
    }

    /**
     * @return true if the broadcast was responded to, false if it was not a broadcast (and there was no response)
     */
    public boolean isBroadcast(final Channel channel, ByteBuf byteBuf, final InetSocketAddress localAddress, InetSocketAddress remoteAddress) {
        if (byteBuf.readableBytes() == 1) {
            // this is a BROADCAST discovery event. Don't read the byte unless it is...
            if (byteBuf.getByte(0) == Broadcast.broadcastID) {
                byteBuf.readByte(); // read the byte to consume it (now that we verified it is a broadcast byte)

                // absolutely MUST send packet > 0 across, otherwise netty will think it failed to write to the socket, and keep trying.
                // (this bug was fixed by netty, however we are keeping this code)
                ByteBuf directBuffer = channel.alloc()
                                              .ioBuffer(1);
                directBuffer.writeByte(Broadcast.broadcastResponseID);

                channel.writeAndFlush(new DatagramPacket(directBuffer, remoteAddress, localAddress));

                logger.info("Responded to host discovery from: {}", remoteAddress);
                return true;
            }
        }

        return false;
    }
}
