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
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 *
 */
public
class BroadcastServer {
    private final org.slf4j.Logger logger;
    private final ByteBuf discoverResponseBuffer;

    public
    BroadcastServer() {
        this.logger = org.slf4j.LoggerFactory.getLogger(BroadcastServer.class.getSimpleName());

        // absolutely MUST send packet > 0 across, otherwise netty will think it failed to write to the socket, and keep trying.
        // (this bug was fixed by netty, however we are keeping this code)
        this.discoverResponseBuffer = Unpooled.buffer(1);
        this.discoverResponseBuffer.writeByte(Broadcast.broadcastResponseID);
    }

    public ByteBuf getBroadcastResponse(ByteBuf byteBuf, InetSocketAddress remoteAddress) {
        if (byteBuf.readableBytes() == 1) {
            // this is a BROADCAST discovery event. Don't read the byte unless it is...
            if (byteBuf.getByte(0) == Broadcast.broadcastID) {
                byteBuf.readByte(); // read the byte to consume it (now that we verified it is a broadcast byte)
                logger.info("Responded to host discovery from: {}", remoteAddress);
                return discoverResponseBuffer;
            }
        }

        return null;
    }
}
