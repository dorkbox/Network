package dorkbox.network.util.udt;

import dorkbox.util.NamedThreadFactory;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;

/**
 * This 'proxy' class exists in order to permit the client/server/endpoint classes from barfing when
 * loading (ie, the JVM classloader), since the classloader only checks ONE level deep for deps, not TWO
 * levels deep.
 * <p>
 * This is an abuse of this, but it does nicely permit us to choose if we want to use UDT or not, without
 * strange complications
 */
public class UdtEndpointProxy {
    public static EventLoopGroup getServerBoss(int threadPoolSize, String name, ThreadGroup nettyGroup) {
        return new NioEventLoopGroup(threadPoolSize, new NamedThreadFactory(name + "-local-boss-UDT", nettyGroup),
                                        io.netty.channel.udt.nio.NioUdtProvider.BYTE_PROVIDER);
    }

    public static EventLoopGroup getServerWorker(int threadPoolSize, String name, ThreadGroup nettyGroup) {
        return new NioEventLoopGroup(threadPoolSize, new NamedThreadFactory(name + "-local-worker-UDT", nettyGroup),
                                        io.netty.channel.udt.nio.NioUdtProvider.BYTE_PROVIDER);
    }

    public static EventLoopGroup getClientWorker(int threadPoolSize, String name, ThreadGroup nettyGroup) {
        return new NioEventLoopGroup(threadPoolSize, new NamedThreadFactory(name + "-remote-UDT", nettyGroup),
                                        io.netty.channel.udt.nio.NioUdtProvider.BYTE_PROVIDER);
    }

    public static void setChannelFactory(ServerBootstrap udtBootstrap) {
        udtBootstrap.channelFactory(io.netty.channel.udt.nio.NioUdtProvider.BYTE_ACCEPTOR);
    }

    public static void setChannelFactory(Bootstrap udtBootstrap) {
        udtBootstrap.channelFactory(io.netty.channel.udt.nio.NioUdtProvider.BYTE_CONNECTOR);
    }

    public static void setLibraryLoaderClassName() {
        com.barchart.udt.ResourceUDT.setLibraryLoaderClassName(UdtJniLoader.class.getName());
    }
}
