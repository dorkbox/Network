package dorkbox.network.connection.registration.remote;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ReferenceCountUtil;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import dorkbox.network.connection.RegistrationWrapper;
import dorkbox.network.connection.registration.MetaChannel;
import dorkbox.network.connection.registration.Registration;
import dorkbox.network.util.SerializationManager;
import dorkbox.util.bytes.OptimizeUtils;
import dorkbox.util.crypto.Crypto;
import dorkbox.util.primativeCollections.IntMap;
import dorkbox.util.primativeCollections.IntMap.Entries;

public class RegistrationRemoteHandlerServerUDT extends RegistrationRemoteHandlerServer {

    public RegistrationRemoteHandlerServerUDT(String name, RegistrationWrapper registrationWrapper, SerializationManager serializationManager) {
        super(name, registrationWrapper, serializationManager);
    }

    /**
     * STEP 1: Channel is first created (This is TCP/UDT only, as such it differs from the client which is TCP/UDP)
     */
    @Override
    protected void initChannel(Channel channel) {
        super.initChannel(channel);
    }

    /**
     * STEP 2: Channel is now active. Prepare the meta channel to listen for the registration process
     */
    @Override
    public void channelActive(ChannelHandlerContext context) throws Exception {
        if (logger.isDebugEnabled()) {
           super.channelActive(context);
        }

        // UDT channels are added when the registration request arrives on a UDT channel.
    }

    /**
     * STEP 3-XXXXX: We pass registration messages around until we the registration handshake is complete!
     */
    @Override
    public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
        Channel channel = context.channel();

        // only TCP will come across here for the server. (UDP here is called by the UDP handler/wrapper)

        if (message instanceof Registration) {
            // find out and make sure that UDP and TCP are talking to the same server
            InetAddress udtRemoteAddress = ((InetSocketAddress) channel.remoteAddress()).getAddress();

            boolean matches = false;
            MetaChannel metaChannel = null;
            try {
                IntMap<MetaChannel> channelMap = registrationWrapper.getAndLockChannelMap();
                Entries<MetaChannel> entries = channelMap.entries();
                while (entries.hasNext()) {
                    metaChannel = entries.next().value;

                    // only look at connections that do not have UDT already setup.
                    if (metaChannel.udtChannel == null) {
                        InetSocketAddress tcpRemote = (InetSocketAddress) metaChannel.tcpChannel.remoteAddress();
                        InetAddress tcpRemoteAddress = tcpRemote.getAddress();

                        if (checkEqual(tcpRemoteAddress, udtRemoteAddress)) {
                            matches = true;
                        } else {
                            logger.error(name, "Mismatch UDT and TCP client addresses! UDP: {}  TCP: {}", udtRemoteAddress, tcpRemoteAddress);
                            shutdown(registrationWrapper, channel);
                            ReferenceCountUtil.release(message);
                            return;
                        }
                    }
                }

            } finally {
                registrationWrapper.releaseChannelMap();
            }

            if (matches && metaChannel != null) {
                // associate TCP and UDT!
                metaChannel.udtChannel = channel;

                Registration register = new Registration();

                // save off the connectionID as a byte array, then encrypt it
                OptimizeUtils optimizeUtils = OptimizeUtils.get();
                int intLength = optimizeUtils.intLength(metaChannel.connectionID, true);
                byte[] idAsBytes = new byte[intLength];
                optimizeUtils.writeInt(idAsBytes, metaChannel.connectionID, true);

                // now encrypt payload via AES
                register.payload = Crypto.AES.encrypt(RegistrationRemoteHandler.getAesEngine(), metaChannel.aesKey, metaChannel.aesIV, idAsBytes);

                // send back, so the client knows that UDP was ok. We include the encrypted connection ID, so the client knows it's a legit server
                channel.writeAndFlush(register);

                // since we are done here, we need to REMOVE this handler
                channel.pipeline().remove(this);

                logger.trace("Register UDT connection from {}", udtRemoteAddress);
                ReferenceCountUtil.release(message);
                return;
            }

            // if we get here, there was a failure!
            logger.error("Error trying to register UDT without udt specified! UDT: {}", udtRemoteAddress);
            shutdown(registrationWrapper, channel);
            ReferenceCountUtil.release(message);
            return;
        }
        else {
            logger.error("UDT attempting to spoof client! Unencrypted packet other than registration received.");
            shutdown(registrationWrapper, channel);
            ReferenceCountUtil.release(message);
            return;
        }
    }
}
