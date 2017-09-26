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
package dorkbox.network;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;

import dorkbox.network.pipeline.discovery.ClientDiscoverHostHandler;
import dorkbox.network.pipeline.discovery.ClientDiscoverHostInitializer;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;

@SuppressWarnings({"unused", "AutoBoxing"})
public final
class Broadcast {
    public static final byte broadcastID = (byte) 42;
    public static final byte broadcastResponseID = (byte) 57;

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger("Broadcast Host Discovery");

    /**
     * Gets the version number.
     */
    public static
    String getVersion() {
        return "2.1";
    }

    /**
     * Broadcasts a UDP message on the LAN to discover any running servers. The address of the first server to respond is returned.
     * <p/>
     * From KryoNet
     *
     * @param udpPort
     *                 The UDP port of the server.
     * @param discoverTimeoutMillis
     *                 The number of milliseconds to wait for a response.
     *
     * @return the first server found, or null if no server responded.
     */
    public static
    String discoverHost(int udpPort, int discoverTimeoutMillis) {
        InetAddress discoverHost = discoverHostAddress(udpPort, discoverTimeoutMillis);
        if (discoverHost != null) {
            return discoverHost.getHostAddress();
        }
        return null;
    }

    /**
     * Broadcasts a UDP message on the LAN to discover any running servers. The address of the first server to respond is returned.
     *
     * @param udpPort
     *                 The UDP port of the server.
     * @param discoverTimeoutMillis
     *                 The number of milliseconds to wait for a response.
     *
     * @return the first server found, or null if no server responded.
     */
    public static
    InetAddress discoverHostAddress(int udpPort, int discoverTimeoutMillis) {
        List<InetAddress> servers = discoverHost0(udpPort, discoverTimeoutMillis, false);
        if (servers.isEmpty()) {
            return null;
        }
        else {
            return servers.get(0);
        }
    }

    /**
     * Broadcasts a UDP message on the LAN to discover all running servers.
     *
     * @param udpPort
     *                 The UDP port of the server.
     * @param discoverTimeoutMillis
     *                 The number of milliseconds to wait for a response.
     *
     * @return the list of found servers (if they responded)
     */
    public static
    List<InetAddress> discoverHosts(int udpPort, int discoverTimeoutMillis) {
        return discoverHost0(udpPort, discoverTimeoutMillis, true);
    }


    private static
    List<InetAddress> discoverHost0(int udpPort, int discoverTimeoutMillis, boolean fetchAllServers) {
        // fetch a buffer that contains the serialized object.
        ByteBuf buffer = Unpooled.buffer(1);
        buffer.writeByte(broadcastID);

        List<InetAddress> servers = new ArrayList<InetAddress>();

        Logger logger2 = logger;

        Enumeration<NetworkInterface> networkInterfaces;
        try {
            networkInterfaces = NetworkInterface.getNetworkInterfaces();
        } catch (SocketException e) {
            logger2.error("Host discovery failed.", e);
            return new ArrayList<InetAddress>(0);
        }


        scan:
        for (NetworkInterface networkInterface : Collections.list(networkInterfaces)) {
            for (InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses()) {
                InetAddress address = interfaceAddress.getAddress();
                InetAddress broadcast = interfaceAddress.getBroadcast();

                // don't use IPv6!
                if (address instanceof Inet6Address) {
                    if (logger2.isInfoEnabled()) {
                        logger2.info("Not using IPv6 address: {}", address);
                    }
                    continue;
                }


                try {
                    if (logger2.isInfoEnabled()) {
                        logger2.info("Searching for host on {} : {}", address, udpPort);
                    }

                    NioEventLoopGroup group = new NioEventLoopGroup();
                    Bootstrap udpBootstrap = new Bootstrap().group(group)
                                                            .channel(NioDatagramChannel.class)
                                                            .option(ChannelOption.SO_BROADCAST, true)
                                                            .handler(new ClientDiscoverHostInitializer())
                                                            .localAddress(new InetSocketAddress(address,
                                                                                                0)); // pick random address. Not listen for broadcast.

                    // we don't care about RECEIVING a broadcast packet, we are only SENDING one.
                    ChannelFuture future;
                    try {
                        future = udpBootstrap.bind();
                        future.await();
                    } catch (InterruptedException e) {
                        logger2.error("Could not bind to random UDP address on the server.", e.getCause());
                        throw new IllegalArgumentException();
                    }

                    if (!future.isSuccess()) {
                        logger2.error("Could not bind to random UDP address on the server.", future.cause());
                        throw new IllegalArgumentException();
                    }

                    Channel channel1 = future.channel();

                    if (broadcast != null) {
                        // try the "defined" broadcast first if we have it (not always!)
                        channel1.writeAndFlush(new DatagramPacket(buffer, new InetSocketAddress(broadcast, udpPort)));

                        // response is received.  If the channel is not closed within 5 seconds, move to the next one.
                        if (!channel1.closeFuture()
                                     .awaitUninterruptibly(discoverTimeoutMillis)) {
                            if (logger2.isInfoEnabled()) {
                                logger2.info("Host discovery timed out.");
                            }
                        }
                        else {
                            InetSocketAddress attachment = channel1.attr(ClientDiscoverHostHandler.STATE)
                                                                   .get();
                            servers.add(attachment.getAddress());
                        }


                        // keep going if we want to fetch all servers. Break if we found one.
                        if (!(fetchAllServers || servers.isEmpty())) {
                            channel1.close()
                                    .await();
                            group.shutdownGracefully()
                                 .await();
                            break scan;
                        }
                    }

                    // continue with "common" broadcast addresses.
                    // Java 1.5 doesn't support getting the subnet mask, so try them until we find one.

                    byte[] ip = address.getAddress();
                    for (int octect = 3; octect >= 0; octect--) {
                        ip[octect] = -1; // 255.255.255.0

                        // don't error out on one particular octect
                        try {
                            InetAddress byAddress = InetAddress.getByAddress(ip);
                            channel1.writeAndFlush(new DatagramPacket(buffer, new InetSocketAddress(byAddress, udpPort)));


                            // response is received.  If the channel is not closed within 5 seconds, move to the next one.
                            if (!channel1.closeFuture()
                                         .awaitUninterruptibly(discoverTimeoutMillis)) {
                                if (logger2.isInfoEnabled()) {
                                    logger2.info("Host discovery timed out.");
                                }
                            }
                            else {
                                InetSocketAddress attachment = channel1.attr(ClientDiscoverHostHandler.STATE)
                                                                       .get();
                                servers.add(attachment.getAddress());
                                if (!fetchAllServers) {
                                    break;
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    }

                    channel1.close()
                            .sync();
                    group.shutdownGracefully(0, discoverTimeoutMillis, TimeUnit.MILLISECONDS);

                } catch (Exception ignored) {
                }

                // keep going if we want to fetch all servers. Break if we found one.
                if (!(fetchAllServers || servers.isEmpty())) {
                    break scan;
                }
            }
        }



        if (logger2.isInfoEnabled() && !servers.isEmpty()) {
            if (fetchAllServers) {
                StringBuilder stringBuilder = new StringBuilder(256);
                stringBuilder.append("Discovered servers: (")
                             .append(servers.size())
                             .append(")");
                for (InetAddress server : servers) {
                    stringBuilder.append("/n")
                                 .append(server)
                                 .append(":")
                                 .append(udpPort);
                }
                logger2.info(stringBuilder.toString());
            }
            else {
                logger2.info("Discovered server: {}:{}", servers.get(0), udpPort);
            }
        }

        return servers;
    }

    private
    Broadcast() {
    }
}

