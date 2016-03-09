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
package dorkbox.network.util.udt;

import dorkbox.util.NamedThreadFactory;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;

/**
 * This 'proxy' class exists in order to permit the client/server/endpoint classes from barfing when loading (ie, the JVM classloader),
 * since the classloader only checks ONE level deep for deps, not TWO levels deep.
 * <p/>
 * This exploits the class loading behavior, but it nicely permits us to choose if we want to use UDT or not, without
 * classloading complications
 */
public
class UdtEndpointProxy {
    public static
    EventLoopGroup getBoss(int threadPoolSize, String name, ThreadGroup nettyGroup) {
        return new NioEventLoopGroup(threadPoolSize,
                                     new NamedThreadFactory(name + "-boss-UDT", nettyGroup),
                                     io.netty.channel.udt.nio.NioUdtProvider.BYTE_PROVIDER);
    }

    public static
    EventLoopGroup getWorker(int threadPoolSize, String name, ThreadGroup nettyGroup) {
        return new NioEventLoopGroup(threadPoolSize,
                                     new NamedThreadFactory(name + "-worker-UDT", nettyGroup),
                                     io.netty.channel.udt.nio.NioUdtProvider.BYTE_PROVIDER);
    }

    public static
    void setChannelFactory(ServerBootstrap udtBootstrap) {
        udtBootstrap.channelFactory(io.netty.channel.udt.nio.NioUdtProvider.BYTE_ACCEPTOR);
    }

    public static
    void setChannelFactory(Bootstrap udtBootstrap) {
        udtBootstrap.channelFactory(io.netty.channel.udt.nio.NioUdtProvider.BYTE_CONNECTOR);
    }
}
