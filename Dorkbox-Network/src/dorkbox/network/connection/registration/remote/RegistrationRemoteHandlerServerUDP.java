package dorkbox.network.connection.registration.remote;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageCodec;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

import dorkbox.network.Broadcast;
import dorkbox.network.connection.Connection;
import dorkbox.network.connection.ConnectionImpl;
import dorkbox.network.connection.EndPoint;
import dorkbox.network.connection.RegistrationWrapper;
import dorkbox.network.connection.registration.MetaChannel;
import dorkbox.network.connection.registration.Registration;
import dorkbox.network.connection.wrapper.UdpWrapper;
import dorkbox.network.util.NetException;
import dorkbox.network.util.SerializationManager;
import dorkbox.network.util.primativeCollections.IntMap;
import dorkbox.network.util.primativeCollections.IntMap.Entries;
import dorkbox.util.bytes.OptimizeUtils;
import dorkbox.util.crypto.Crypto;

@Sharable
public class RegistrationRemoteHandlerServerUDP extends MessageToMessageCodec<DatagramPacket, UdpWrapper> {

    // this is for the SERVER only. UDP channel is ALWAYS the SAME channel (it's the server's listening channel).

    private final org.slf4j.Logger logger;
    private final String name;
    private final ByteBuf discoverResponseBuffer;
    private final RegistrationWrapper registrationWrapper;
    private final SerializationManager serializationManager;


    public RegistrationRemoteHandlerServerUDP(String name, RegistrationWrapper registrationWrapper, SerializationManager serializationManager) {
        this.name = name + " Registration-UDP-Server";
        logger = org.slf4j.LoggerFactory.getLogger(this.name);
        this.registrationWrapper = registrationWrapper;
        this.serializationManager = serializationManager;

        // absolutely MUST send packet > 0 across, otherwise netty will think it failed to write to the socket, and keep trying. (bug was fixed by netty. Keeping this code)
        discoverResponseBuffer = Unpooled.buffer(1);
        discoverResponseBuffer.writeByte(Broadcast.broadcastResponseID);
    }

    /**
     * STEP 2: Channel is now active. We are now LISTENING to UDP messages!
     */
    @Override
    public void channelActive(ChannelHandlerContext context) throws Exception {
        // do NOT want to add UDP channels, since they are tracked differently for the server.
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) throws Exception {
        // log UDP errors.
        logger.error("Exception caught in UDP stream.", cause);
        super.exceptionCaught(context, cause);
    }

    @Override
    protected void encode(ChannelHandlerContext context, UdpWrapper msg, List<Object> out) throws Exception {
        Object object = msg.object();
        InetSocketAddress remoteAddress = msg.remoteAddress();

        if (object instanceof ByteBuf) {
            // this is the response from a discoverHost query
            out.add(new DatagramPacket((ByteBuf) object, remoteAddress));
        } else {
            ByteBuf buffer = Unpooled.buffer(EndPoint.udpMaxSize);

            sendUDP(context, object, buffer, remoteAddress);

            if (buffer != null) {
                out.add(new DatagramPacket(buffer, remoteAddress));
            }
        }
    }

    @Override
    protected void decode(ChannelHandlerContext context, DatagramPacket msg, List<Object> out) throws Exception {
        Channel channel = context.channel();
        ByteBuf data = msg.content();
        InetSocketAddress remoteAddress = msg.sender();

        // must have a remote address in the packet. (ie, ignore broadcast)
        if (remoteAddress == null) {
            logger.debug("Ignoring packet with null UDP remote address. (Is it broadcast?)");
            return;
        }

        if (data.readableBytes() == 1) {
            if (data.readByte() == Broadcast.broadcastID) {
                // CANNOT use channel.getRemoteAddress()
                channel.writeAndFlush(new UdpWrapper(discoverResponseBuffer, remoteAddress));
                logger.debug("Responded to host discovery from: {}", remoteAddress);
            } else {
                logger.error("Invalid signature for 'Discover Host' from remote address: {}", remoteAddress);
            }
        } else {
            // we cannot use the REGULAR pipeline, since we can't pass along the remote address for
            // when we establish the "network connection"

            // send on the message, now that we have the WRITE channel figured out and the data.
            receivedUDP(context, channel, data, remoteAddress);
        }
    }


    public final void sendUDP(ChannelHandlerContext context, Object object, ByteBuf buffer, InetSocketAddress udpRemoteAddress) {

        Connection networkConnection = registrationWrapper.getServerUDP(udpRemoteAddress);
        if (networkConnection != null) {
            // try to write data! (IT SHOULD ALWAYS BE ENCRYPTED HERE!)
            serializationManager.writeWithCryptoUdp(networkConnection, buffer, object);
        } else {
            // this means we are still in the REGISTRATION phase.
            serializationManager.write(buffer, object);
        }
    }


    // this will be invoked by the UdpRegistrationHandlerServer. Remember, TCP will be established first.
    public final void receivedUDP(ChannelHandlerContext context, Channel channel, ByteBuf data, InetSocketAddress udpRemoteAddress) throws Exception {
        // registration is the ONLY thing NOT encrypted
        if (serializationManager.isEncrypted(data)) {
            // we need to FORWARD this message "down the pipeline".

            ConnectionImpl connection = registrationWrapper.getServerUDP(udpRemoteAddress);
            if (connection != null) {
                // try to read data! (IT SHOULD ALWAYS BE ENCRYPTED HERE!)
                Object object;

                try {
                    object = serializationManager.readWithCryptoUdp(connection, data, data.writerIndex());
                } catch (NetException e) {
                    logger.error("UDP unable to deserialize buffer", e);
                    shutdown(registrationWrapper, channel);
                    return;
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
                object = serializationManager.read(data, data.writerIndex());
            } catch (NetException e) {
                logger.error("UDP unable to deserialize buffer", e);
                shutdown(registrationWrapper, channel);
                return;
            }

            if (object instanceof Registration) {
                boolean matches = false;
                MetaChannel metaChannel = null;

                try {
                    // find out and make sure that UDP and TCP are talking to the same server
                    InetAddress udpRemoteServer = udpRemoteAddress.getAddress();
                    IntMap<MetaChannel> channelMap = registrationWrapper.getAndLockChannelMap();
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
                            } else {
                                logger.error("Mismatch UDP and TCP client addresses! UDP: {}  TCP: {}", udpRemoteServer, tcpRemoteAddress);
                                shutdown(registrationWrapper, channel);
                                return;
                            }
                        }
                    }
                } finally {
                    registrationWrapper.releaseChannelMap();
                }


                if (matches && metaChannel != null) {
                    // associate TCP and UDP!
                    metaChannel.udpChannel = channel;
                    metaChannel.udpRemoteAddress = udpRemoteAddress;

                    Registration register = new Registration();

                    // save off the connectionID as a byte array, then encrypt it
                    OptimizeUtils optimizeUtils = OptimizeUtils.get();
                    int intLength = optimizeUtils.intLength(metaChannel.connectionID, true);
                    byte[] idAsBytes = new byte[intLength];
                    optimizeUtils.writeInt(idAsBytes, metaChannel.connectionID, true);

                    // now encrypt payload via AES
                    register.payload = Crypto.AES.encrypt(RegistrationRemoteHandler.getAesEngine(), metaChannel.aesKey, metaChannel.aesIV, idAsBytes);

                    channel.writeAndFlush(new UdpWrapper(register, udpRemoteAddress));

                    logger.trace("Register UDP connection from {}", udpRemoteAddress);
                    return;
                }

                // if we get here, there was a failure!
                logger.error("Error trying to register UDP without udp specified! UDP: {}", udpRemoteAddress);
                shutdown(registrationWrapper, channel);
                return;
            }
            else {
                logger.error("UDP attempting to spoof client! Unencrypted packet other than registration received.");
                shutdown(null, channel);
                return;
            }
        }
    }

    /**
     * Copied from RegistrationHandler. There were issues accessing it as static with generics.
     */
    public MetaChannel shutdown(RegistrationWrapper registrationWrapper, Channel channel) {
        logger.error("SHUTDOWN HANDLER REACHED! SOMETHING MESSED UP! TRYING TO ABORT");

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
