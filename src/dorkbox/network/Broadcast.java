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

import dorkbox.network.pipeline.MagicBytes;
import dorkbox.network.pipeline.discovery.BroadcastResponse;
import dorkbox.network.pipeline.discovery.ClientDiscoverHostHandler;
import dorkbox.network.pipeline.discovery.ClientDiscoverHostInitializer;
import dorkbox.util.OS;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.oio.OioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.oio.OioDatagramChannel;

@SuppressWarnings({"unused", "AutoBoxing"})
public final
class Broadcast {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(Client.class.getSimpleName());

    /**
     * Gets the version number.
     */
    public static
    String getVersion() {
        return "2.15";
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
    BroadcastResponse discoverHost(int udpPort, int discoverTimeoutMillis) {
        BroadcastResponse discoverHost = discoverHostAddress(udpPort, discoverTimeoutMillis);
        if (discoverHost != null) {
            return discoverHost;
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
    BroadcastResponse discoverHostAddress(int udpPort, int discoverTimeoutMillis) {
        List<BroadcastResponse> servers = discoverHosts0(logger, udpPort, discoverTimeoutMillis, false);
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
    List<BroadcastResponse> discoverHosts(int udpPort, int discoverTimeoutMillis) {
        return discoverHosts0(logger, udpPort, discoverTimeoutMillis, true);
    }


    static
    List<BroadcastResponse> discoverHosts0(Logger logger, int udpPort, int discoverTimeoutMillis, boolean fetchAllServers) {
        // fetch a buffer that contains the serialized object.
        ByteBuf buffer = Unpooled.buffer(1);
        buffer.writeByte(MagicBytes.broadcastID);

        List<BroadcastResponse> servers = new ArrayList<BroadcastResponse>();

        Enumeration<NetworkInterface> networkInterfaces;
        try {
            networkInterfaces = NetworkInterface.getNetworkInterfaces();
        } catch (SocketException e) {
            if (logger != null) {
                logger.error("Host discovery failed.", e);
            }
            return new ArrayList<BroadcastResponse>(0);
        }


        scan:
        for (NetworkInterface networkInterface : Collections.list(networkInterfaces)) {
            for (InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses()) {
                InetAddress address = interfaceAddress.getAddress();
                InetAddress broadcast = interfaceAddress.getBroadcast();

                // don't use IPv6!
                if (address instanceof Inet6Address) {
                    if (logger != null) {
                        if (logger.isInfoEnabled()) {
                            logger.info("Not using IPv6 address: {}", address);
                        }
                    }
                    continue;
                }


                try {
                    if (logger != null) {
                        if (logger.isInfoEnabled()) {
                            logger.info("Searching for host on [{}:{}]", address.getHostAddress(), udpPort);
                        }
                    }

                    EventLoopGroup group;
                    Class<? extends Channel> channelClass;

                    if (OS.isAndroid()) {
                        // android ONLY supports OIO (not NIO)
                        group = new OioEventLoopGroup(1);
                        channelClass = OioDatagramChannel.class;
                    } else {
                        group = new NioEventLoopGroup(1);
                        channelClass = NioDatagramChannel.class;
                    }


                    Bootstrap udpBootstrap = new Bootstrap().group(group)
                                                            .channel(channelClass)
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
                        if (logger != null) {
                            logger.error("Could not bind to random UDP address on the server.", e.getCause());
                        }
                        throw new IllegalArgumentException("Could not bind to random UDP address on the server.");
                    }

                    if (!future.isSuccess()) {
                        if (logger != null) {
                            logger.error("Could not bind to random UDP address on the server.", future.cause());
                        }
                        throw new IllegalArgumentException("Could not bind to random UDP address on the server.");
                    }

                    Channel channel1 = future.channel();

                    if (broadcast != null) {
                        // try the "defined" broadcast first if we have it (not always!)
                        channel1.writeAndFlush(new DatagramPacket(buffer, new InetSocketAddress(broadcast, udpPort)));

                        // response is received.  If the channel is not closed within 5 seconds, move to the next one.
                        if (!channel1.closeFuture().awaitUninterruptibly(discoverTimeoutMillis)) {
                            if (logger != null) {
                                if (logger.isInfoEnabled()) {
                                    logger.info("Host discovery timed out.");
                                }
                            }
                        }
                        else {
                            BroadcastResponse broadcastResponse = channel1.attr(ClientDiscoverHostHandler.STATE).get();
                            servers.add(broadcastResponse);
                        }


                        // keep going if we want to fetch all servers. Break if we found one.
                        if (!(fetchAllServers || servers.isEmpty())) {
                            channel1.close().await();
                            group.shutdownGracefully().await();
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
                            if (!channel1.closeFuture().awaitUninterruptibly(discoverTimeoutMillis)) {
                                if (logger != null) {
                                    if (logger.isInfoEnabled()) {
                                        logger.info("Host discovery timed out.");
                                    }
                                }
                            }
                            else {
                                BroadcastResponse broadcastResponse = channel1.attr(ClientDiscoverHostHandler.STATE).get();
                                servers.add(broadcastResponse);

                                if (!fetchAllServers) {
                                    break;
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    }

                    channel1.close().sync();
                    group.shutdownGracefully(0, discoverTimeoutMillis, TimeUnit.MILLISECONDS);

                } catch (Exception ignored) {
                }

                // keep going if we want to fetch all servers. Break if we found one.
                if (!(fetchAllServers || servers.isEmpty())) {
                    break scan;
                }
            }
        }


        if (logger != null && logger.isInfoEnabled() && !servers.isEmpty()) {
            StringBuilder stringBuilder = new StringBuilder(256);

            if (fetchAllServers) {
                stringBuilder.append("Discovered servers: (")
                             .append(servers.size())
                             .append(")");

                for (BroadcastResponse server : servers) {
                    stringBuilder.append("/n")
                                 .append(server.remoteAddress)
                                 .append(":");

                    if (server.tcpPort > 0) {
                        stringBuilder.append(server.tcpPort);

                        if (server.udpPort > 0) {
                            stringBuilder.append(":");
                        }
                    }
                    if (server.udpPort > 0) {
                        stringBuilder.append(udpPort);
                    }
                }
                logger.info(stringBuilder.toString());
            }
            else {
                BroadcastResponse server = servers.get(0);
                stringBuilder.append(server.remoteAddress)
                             .append(":");

                if (server.tcpPort > 0) {
                    stringBuilder.append(server.tcpPort);

                    if (server.udpPort > 0) {
                        stringBuilder.append(":");
                    }
                }
                if (server.udpPort > 0) {
                    stringBuilder.append(udpPort);
                }

                logger.info("Discovered server [{}]", stringBuilder.toString());
            }
        }

        return servers;
    }

    private
    Broadcast() {
    }
}

