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
package dorkbox.network.connection.registration.remote;

import dorkbox.network.Broadcast;
import dorkbox.network.connection.Connection;
import dorkbox.network.connection.ConnectionImpl;
import dorkbox.network.connection.KryoCryptoSerializationManager;
import dorkbox.network.connection.RegistrationWrapper;
import dorkbox.network.connection.registration.MetaChannel;
import dorkbox.network.connection.registration.Registration;
import dorkbox.network.connection.wrapper.UdpWrapper;
import dorkbox.network.util.CryptoSerializationManager;
import dorkbox.util.bytes.OptimizeUtilsByteArray;
import dorkbox.util.collections.IntMap;
import dorkbox.util.collections.IntMap.Entries;
import dorkbox.util.crypto.Crypto;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageCodec;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

@Sharable
public
class RegistrationRemoteHandlerServerUDP<C extends Connection> extends MessageToMessageCodec<DatagramPacket, UdpWrapper> {

    // this is for the SERVER only. UDP channel is ALWAYS the SAME channel (it's the server's listening channel).

    private final org.slf4j.Logger logger;
    private final ByteBuf discoverResponseBuffer;
    private final RegistrationWrapper<C> registrationWrapper;
    private final CryptoSerializationManager serializationManager;


    public
    RegistrationRemoteHandlerServerUDP(final String name,
                                       final RegistrationWrapper<C> registrationWrapper,
                                       final CryptoSerializationManager serializationManager) {
        final String name1 = name + " Registration-UDP-Server";
        this.logger = org.slf4j.LoggerFactory.getLogger(name1);
        this.registrationWrapper = registrationWrapper;
        this.serializationManager = serializationManager;

        // absolutely MUST send packet > 0 across, otherwise netty will think it failed to write to the socket, and keep trying. (bug was fixed by netty. Keeping this code)
        this.discoverResponseBuffer = Unpooled.buffer(1);
        this.discoverResponseBuffer.writeByte(Broadcast.broadcastResponseID);
    }

    /**
     * STEP 2: Channel is now active. We are now LISTENING to UDP messages!
     */
    @Override
    public
    void channelActive(final ChannelHandlerContext context) throws Exception {
        // do NOT want to add UDP channels, since they are tracked differently for the server.
    }

    @Override
    public
    void exceptionCaught(final ChannelHandlerContext context, final Throwable cause) throws Exception {
        // log UDP errors.
        this.logger.error("Exception caught in UDP stream.", cause);
        super.exceptionCaught(context, cause);
    }

    @Override
    protected
    void encode(final ChannelHandlerContext context, final UdpWrapper msg, final List<Object> out) throws Exception {
        Object object = msg.object();
        InetSocketAddress remoteAddress = msg.remoteAddress();

        if (object instanceof ByteBuf) {
            // this is the response from a discoverHost query
            out.add(new DatagramPacket((ByteBuf) object, remoteAddress));
        }
        else {
            // this is regular registration stuff
            ByteBuf buffer = context.alloc()
                                    .buffer();

            // writes data into buffer
            try {
                ConnectionImpl networkConnection = this.registrationWrapper.getServerUDP(remoteAddress);
                if (networkConnection != null) {
                    // try to write data! (IT SHOULD ALWAYS BE ENCRYPTED HERE!)
                    this.serializationManager.writeWithCryptoUdp(networkConnection, buffer, object);
                }
                else {
                    // this means we are still in the REGISTRATION phase.
                    this.serializationManager.write(buffer, object);
                }

                if (buffer != null) {
                    out.add(new DatagramPacket(buffer, remoteAddress));
                }
            } catch (IOException e) {
                logger.error("Unable to write data to the socket.", e);
                throw e;
            }
        }
    }

    @Override
    protected
    void decode(final ChannelHandlerContext context, final DatagramPacket msg, final List<Object> out) throws Exception {
        Channel channel = context.channel();
        ByteBuf data = msg.content();
        InetSocketAddress remoteAddress = msg.sender();

        // must have a remote address in the packet. (ie, ignore broadcast)
        Logger logger2 = this.logger;
        if (remoteAddress == null) {
            if (logger2.isDebugEnabled()) {
                logger2.debug("Ignoring packet with null UDP remote address. (Is it broadcast?)");
            }
            return;
        }

        if (data.readableBytes() == 1) {
            if (data.readByte() == Broadcast.broadcastID) {
                // CANNOT use channel.getRemoteAddress()
                channel.writeAndFlush(new UdpWrapper(this.discoverResponseBuffer, remoteAddress));
                if (logger2.isDebugEnabled()) {
                    logger2.debug("Responded to host discovery from: {}", remoteAddress);
                }
            }
            else {
                logger2.error("Invalid signature for 'Discover Host' from remote address: {}", remoteAddress);
            }
        }
        else {
            // we cannot use the REGULAR pipeline, since we can't pass along the remote address for
            // when we establish the "network connection"

            // send on the message, now that we have the WRITE channel figured out and the data.
            receivedUDP(context, channel, data, remoteAddress);
        }
    }


    // this will be invoked by the UdpRegistrationHandlerServer. Remember, TCP will be established first.
    @SuppressWarnings({"unused", "AutoUnboxing"})
    private
    void receivedUDP(final ChannelHandlerContext context,
                     final Channel channel,
                     final ByteBuf data,
                     final InetSocketAddress udpRemoteAddress) throws Exception {
        // registration is the ONLY thing NOT encrypted
        Logger logger2 = this.logger;
        RegistrationWrapper<C> registrationWrapper2 = this.registrationWrapper;
        CryptoSerializationManager serializationManager2 = this.serializationManager;

        if (KryoCryptoSerializationManager.isEncrypted(data)) {
            // we need to FORWARD this message "down the pipeline".

            ConnectionImpl connection = registrationWrapper2.getServerUDP(udpRemoteAddress);
            //noinspection StatementWithEmptyBody
            if (connection != null) {
                // try to read data! (IT SHOULD ALWAYS BE ENCRYPTED HERE!)
                Object object;

                try {
                    object = serializationManager2.readWithCryptoUdp(connection, data, data.writerIndex());
                } catch (Exception e) {
                    logger2.error("UDP unable to deserialize buffer", e);
                    shutdown(registrationWrapper2, channel);
                    throw e;
                }

                connection.channelRead(object);
            }
            // if we don't have this "from" IP address ALREADY registered, drop the packet.
            // OR the channel was shutdown while it was still receiving data.
            else {
                // we DON'T CARE about this, so we will just ignore the incoming message.
            }
        }
        // manage the registration packets!
        else {
            Object object;

            try {
                object = serializationManager2.read(data, data.writerIndex());
            } catch (Exception e) {
                logger2.error("UDP unable to deserialize buffer", e);
                shutdown(registrationWrapper2, channel);
                return;
            }

            if (object instanceof Registration) {
                boolean matches = false;
                MetaChannel metaChannel = null;

                try {
                    // find out and make sure that UDP and TCP are talking to the same server
                    InetAddress udpRemoteServer = udpRemoteAddress.getAddress();
                    IntMap<MetaChannel> channelMap = registrationWrapper2.getAndLockChannelMap();
                    Entries<MetaChannel> entries = channelMap.entries();

                    while (entries.hasNext()) {
                        metaChannel = entries.next().value;

                        // only look at connections that do not have UDP already setup.
                        if (metaChannel.udpChannel == null) {
                            InetSocketAddress tcpRemote = (InetSocketAddress) metaChannel.tcpChannel.remoteAddress();
                            InetAddress tcpRemoteAddress = tcpRemote.getAddress();

                            if (RegistrationRemoteHandler.checkEqual(tcpRemoteAddress, udpRemoteServer)) {
                                matches = true;
                                break;
                            }
                            else {
                                logger2.error("Mismatch UDP and TCP client addresses! UDP: {}  TCP: {}", udpRemoteServer, tcpRemoteAddress);
                                shutdown(registrationWrapper2, channel);
                                return;
                            }
                        }
                    }
                } finally {
                    registrationWrapper2.releaseChannelMap();
                }


                if (matches) {
                    // associate TCP and UDP!
                    metaChannel.udpChannel = channel;
                    metaChannel.udpRemoteAddress = udpRemoteAddress;

                    Registration register = new Registration();

                    // save off the connectionID as a byte array, then encrypt it
                    OptimizeUtilsByteArray optimizeUtils = OptimizeUtilsByteArray.get();
                    int intLength = optimizeUtils.intLength(metaChannel.connectionID, true);
                    byte[] idAsBytes = new byte[intLength];
                    optimizeUtils.writeInt(idAsBytes, metaChannel.connectionID, true);

                    // now encrypt payload via AES
                    register.payload = Crypto.AES.encrypt(RegistrationRemoteHandler.getAesEngine(),
                                                          metaChannel.aesKey,
                                                          metaChannel.aesIV,
                                                          idAsBytes,
                                                          logger);

                    channel.writeAndFlush(new UdpWrapper(register, udpRemoteAddress));
                    if (logger2.isTraceEnabled()) {
                        logger2.trace("Register UDP connection from {}", udpRemoteAddress);
                    }
                }
                else {
                    // if we get here, there was a failure!
                    logger2.error("Error trying to register UDP without udp specified! UDP: {}", udpRemoteAddress);
                    shutdown(registrationWrapper2, channel);
                }
            }
            else {
                logger2.error("UDP attempting to spoof client! Unencrypted packet other than registration received.");
                shutdown(null, channel);
            }
        }
    }

    /**
     * Copied from RegistrationHandler. There were issues accessing it as static with generics.
     */
    public
    MetaChannel shutdown(final RegistrationWrapper<C> registrationWrapper, final Channel channel) {
        this.logger.error("SHUTDOWN HANDLER REACHED! SOMETHING MESSED UP! TRYING TO ABORT");

        // shutdown. Something messed up. Only reach this is something messed up.
        // properly shutdown the TCP/UDP channels.
        if (channel.isOpen()) {
            channel.close();
        }

        // also, once we notify, we unregister this.
        if (registrationWrapper != null) {
            try {
                IntMap<MetaChannel> channelMap = registrationWrapper.getAndLockChannelMap();
                Entries<MetaChannel> entries = channelMap.entries();
                while (entries.hasNext()) {
                    MetaChannel metaChannel = entries.next().value;
                    if (metaChannel.localChannel == channel || metaChannel.tcpChannel == channel || metaChannel.udpChannel == channel) {
                        entries.remove();
                        metaChannel.close();
                        return metaChannel;
                    }
                }

            } finally {
                registrationWrapper.releaseChannelMap();
            }
        }

        return null;
    }
}
