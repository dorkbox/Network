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

import java.net.InetAddress;
import java.net.InetSocketAddress;

import dorkbox.network.Server;
import dorkbox.network.connection.EndPoint;
import dorkbox.network.pipeline.MagicBytes;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.socket.DatagramPacket;

/**
 * Manages the response to broadcast events
 */
public
class BroadcastServer {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(Server.class.getSimpleName());

    private final int tcpPort;
    private final int udpPort;

    private final int bufferSize;

    public
    BroadcastServer() {
        this.bufferSize = 0;
        this.tcpPort = 0;
        this.udpPort = 0;
    }

    public
    BroadcastServer(final int tcpPort, final int udpPort) {
        this.tcpPort = tcpPort;
        this.udpPort = udpPort;

        // either it will be TCP or UDP, or BOTH
        if (tcpPort > 0 ^ udpPort > 0) {
            // TCP or UDP

            // ID + TCP or UDP ID + TCP or UDP port
            bufferSize = 4;
        }
        else {
            // BOTH

            // ID + TCP and UDP ID + TCP and UDP port
            bufferSize = 6;
        }
    }


    /**
     * @return true if the broadcast was responded to, false if it was not a broadcast (and there was no response)
     */
    public boolean isDiscoveryRequest(final Channel channel, ByteBuf byteBuf, final InetSocketAddress localAddress, InetSocketAddress remoteAddress) {
        if (byteBuf.readableBytes() == 1) {
            // this is a BROADCAST discovery event. Don't read the byte unless it is...
            if (byteBuf.getByte(0) == MagicBytes.broadcastID) {
                byteBuf.readByte(); // read the byte to consume it (now that we verified it is a broadcast byte)

                // absolutely MUST send packet > 0 across, otherwise netty will think it failed to write to the socket, and keep trying.
                // (this bug was fixed by netty, however we are keeping this code)
                ByteBuf directBuffer = channel.alloc()
                                              .ioBuffer(bufferSize);
                directBuffer.writeByte(MagicBytes.broadcastResponseID);

                // now output the port information for TCP/UDP so the broadcast client knows which port to connect to
                // either it will be TCP or UDP, or BOTH

                int enabledFlag = 0;
                if (tcpPort > 0) {
                    enabledFlag |= MagicBytes.HAS_TCP;
                }

                if (udpPort > 0) {
                    enabledFlag |= MagicBytes.HAS_UDP;
                }

                directBuffer.writeByte(enabledFlag);

                // TCP is always first
                if (tcpPort > 0) {
                    directBuffer.writeShort(tcpPort);
                }

                if (udpPort > 0) {
                    directBuffer.writeShort(udpPort);
                }


                channel.writeAndFlush(new DatagramPacket(directBuffer, remoteAddress, localAddress));

                logger.info("Responded to host discovery from [{}]", EndPoint.getHostDetails(remoteAddress));

                byteBuf.release();
                return true;
            }
        }

        return false;
    }


    /**
     * @return true if this is a broadcast response, false if it was not a broadcast response
     */
    public static
    boolean isDiscoveryResponse(ByteBuf byteBuf, final InetAddress remoteAddress, final Channel channel) {
        if (byteBuf.readableBytes() <= MagicBytes.maxPacketSize) {
            // this is a BROADCAST discovery RESPONSE event. Don't read the byte unless it is...
            if (byteBuf.getByte(0) == MagicBytes.broadcastResponseID) {
                byteBuf.readByte(); // read the byte to consume it (now that we verified it is a broadcast byte)

                // either it will be TCP or UDP, or BOTH
                int typeID = byteBuf.readByte();

                int tcpPort = 0;
                int udpPort = 0;

                // TCP is always first
                if ((typeID & MagicBytes.HAS_TCP) == MagicBytes.HAS_TCP) {
                    tcpPort = byteBuf.readUnsignedShort();
                }

                if ((typeID & MagicBytes.HAS_UDP) == MagicBytes.HAS_UDP) {
                    udpPort = byteBuf.readUnsignedShort();
                }

                channel.attr(ClientDiscoverHostHandler.STATE)
                       .set(new BroadcastResponse(remoteAddress, tcpPort, udpPort));

                byteBuf.release();
                return true;
            }
        }

        return false;
    }
}
