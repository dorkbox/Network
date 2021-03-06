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
package dorkbox.network.other.discovery.server;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.nio.NioDatagramChannel;

/**
 * Creates a newly configured {@link ChannelPipeline} for a new channel.
 */
public
class ClientDiscoverHostInitializer extends ChannelInitializer<NioDatagramChannel> {
    private final ClientDiscoverHostHandler clientDiscoverHostHandler;

    public
    ClientDiscoverHostInitializer() {
        clientDiscoverHostHandler = new ClientDiscoverHostHandler();
    }

    @Override
    public
    void initChannel(final NioDatagramChannel ch) throws Exception {
        ChannelPipeline pipeline = ch.pipeline();

        pipeline.addLast("discoverHostHandler", clientDiscoverHostHandler);
    }
}
