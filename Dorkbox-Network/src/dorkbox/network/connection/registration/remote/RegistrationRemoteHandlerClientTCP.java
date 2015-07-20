package dorkbox.network.connection.registration.remote;

import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import dorkbox.network.connection.RegistrationWrapper;
import dorkbox.network.connection.registration.MetaChannel;
import dorkbox.network.connection.registration.Registration;
import dorkbox.network.util.CryptoSerializationManager;
import dorkbox.network.util.exceptions.SecurityException;
import dorkbox.util.bytes.OptimizeUtilsByteArray;
import dorkbox.util.collections.IntMap;
import dorkbox.util.crypto.Crypto;
import dorkbox.util.crypto.serialization.EccPublicKeySerializer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ReferenceCountUtil;
import org.bouncycastle.crypto.BasicAgreement;
import org.bouncycastle.crypto.agreement.ECDHCBasicAgreement;
import org.bouncycastle.crypto.digests.SHA384Digest;
import org.bouncycastle.crypto.engines.IESEngine;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECParameterSpec;
import org.slf4j.Logger;

import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

public
class RegistrationRemoteHandlerClientTCP extends RegistrationRemoteHandlerClient {

    private static final String DELETE_IP = "eleteIP"; // purposefully missing the "D", since that is a system parameter, which starts with "-D"
    private final static ECParameterSpec eccSpec = ECNamedCurveTable.getParameterSpec(Crypto.ECC.p521_curve);
    private ThreadLocal<IESEngine> eccEngineLocal = new ThreadLocal<IESEngine>();

    public
    RegistrationRemoteHandlerClientTCP(String name,
                                       RegistrationWrapper registrationWrapper,
                                       CryptoSerializationManager serializationManager) {
        super(name, registrationWrapper, serializationManager);

        // check to see if we need to delete an IP address as commanded from the user prompt
        String ipAsString = System.getProperty(DELETE_IP);
        if (ipAsString != null) {
            System.setProperty(DELETE_IP, "");
            byte[] address = null;
            try {
                String[] split = ipAsString.split("\\.");
                if (split.length == 4) {
                    address = new byte[4];
                    for (int i = 0; i < split.length; i++) {
                        int asInt = Integer.parseInt(split[i]);
                        if (asInt >= 0 && asInt <= 255) {
                            address[i] = (byte) Integer.parseInt(split[i]);
                        }
                        else {
                            address = null;
                            break;
                        }

                    }
                }
            } catch (Exception e) {
                address = null;
            }

            if (address != null) {
                try {
                    registrationWrapper.removeRegisteredServerKey(address);
                } catch (SecurityException e) {
                    this.logger.error(e.getMessage(), e);
                }
            }
        }
        // end command
    }

    private final
    IESEngine getEccEngine() {
        IESEngine iesEngine = this.eccEngineLocal.get();
        if (iesEngine == null) {
            iesEngine = Crypto.ECC.createEngine();
            this.eccEngineLocal.set(iesEngine);
        }
        return iesEngine;
    }

    /**
     * STEP 1: Channel is first created
     */
    @Override
    protected
    void initChannel(Channel channel) {
        this.logger.trace("Channel registered: {}",
                          channel.getClass()
                                 .getSimpleName());


        // TCP & UDT

        // use the default.
        super.initChannel(channel);
    }

    /**
     * STEP 2: Channel is now active. Start the registration process
     */
    @Override
    public
    void channelActive(ChannelHandlerContext context) throws Exception {
        Logger logger2 = this.logger;
        if (logger2.isDebugEnabled()) {
            super.channelActive(context);
        }

        Channel channel = context.channel();

        // look to see if we already have a connection (in progress) for the destined IP address.
        // Note: our CHANNEL MAP can only have one item at a time, since we do NOT RELEASE the registration lock until it's complete!!

        // The ORDER has to be TCP (always) -> UDP (optional) -> UDT (optional)
        // TCP
        MetaChannel metaChannel = new MetaChannel();
        metaChannel.tcpChannel = channel;

        try {
            IntMap<MetaChannel> channelMap = this.registrationWrapper.getAndLockChannelMap();
            channelMap.put(channel.hashCode(), metaChannel);
        } finally {
            this.registrationWrapper.releaseChannelMap();
        }

        if (logger2.isTraceEnabled()) {
            logger2.trace("Start new TCP Connection. Sending request to server");
        }

        Registration registration = new Registration();
        registration.publicKey = this.registrationWrapper.getPublicKey();

        // client start the handshake with a registration packet
        channel.writeAndFlush(registration);
    }

    @Override
    public
    void channelRead(ChannelHandlerContext context, Object message) throws Exception {
        Channel channel = context.channel();

        RegistrationWrapper registrationWrapper2 = this.registrationWrapper;
        Logger logger2 = this.logger;
        if (message instanceof Registration) {
            // make sure this connection was properly registered in the map. (IT SHOULD BE)
            MetaChannel metaChannel = null;
            try {
                IntMap<MetaChannel> channelMap = registrationWrapper2.getAndLockChannelMap();
                metaChannel = channelMap.get(channel.hashCode());
            } finally {
                registrationWrapper2.releaseChannelMap();
            }

            if (metaChannel != null) {
                metaChannel.updateTcpRoundTripTime();

                Registration registration = (Registration) message;

                if (metaChannel.connectionID == null) {
                    // want to validate the public key used! This is similar to how SSH works, in that once we use a public key, we want to validate
                    // against that ip-address::key pair, so we can better protect against MITM/spoof attacks.
                    InetSocketAddress tcpRemoteServer = (InetSocketAddress) channel.remoteAddress();

                    boolean valid = registrationWrapper2.validateRemoteServerAddress(tcpRemoteServer, registration.publicKey);

                    if (!valid) {
                        //whoa! abort since something messed up! (log happens inside of validate method)
                        String hostAddress = tcpRemoteServer.getAddress()
                                                            .getHostAddress();
                        logger2.error("Invalid ECC public key for server IP {} during handshake. WARNING. The server has changed!",
                                      hostAddress);
                        logger2.error("Fix by adding the argument   -D{} {}   when starting the client.", DELETE_IP, hostAddress);
                        metaChannel.changedRemoteKey = true;

                        shutdown(registrationWrapper2, channel);

                        ReferenceCountUtil.release(message);
                        return;
                    }

                    // setup crypto state
                    IESEngine decrypt = getEccEngine();

                    byte[] aesKeyBytes = Crypto.ECC.decrypt(decrypt,
                                                            registrationWrapper2.getPrivateKey(),
                                                            registration.publicKey,
                                                            registration.eccParameters,
                                                            registration.aesKey);

                    if (aesKeyBytes.length != 32) {
                        logger2.error("Invalid decryption of aesKey. Aborting.");
                        shutdown(registrationWrapper2, channel);

                        ReferenceCountUtil.release(message);
                        return;
                    }

                    // now decrypt payload using AES
                    byte[] payload = Crypto.AES.decrypt(getAesEngine(), aesKeyBytes, registration.aesIV, registration.payload);

                    if (payload.length == 0) {
                        logger2.error("Invalid decryption of payload. Aborting.");
                        shutdown(registrationWrapper2, channel);

                        ReferenceCountUtil.release(message);
                        return;
                    }

                    OptimizeUtilsByteArray optimizeUtils = OptimizeUtilsByteArray.get();
                    if (!optimizeUtils.canReadInt(payload)) {
                        logger2.error("Invalid decryption of connection ID. Aborting.");
                        shutdown(registrationWrapper2, channel);

                        ReferenceCountUtil.release(message);
                        return;
                    }

                    metaChannel.connectionID = optimizeUtils.readInt(payload, true);
                    int intLength = optimizeUtils.intLength(metaChannel.connectionID, true);

                    /*
                     * Diffie-Hellman-Merkle key exchange for the AES key
                     * see http://en.wikipedia.org/wiki/Diffie%E2%80%93Hellman_key_exchange
                     */
                    byte[] ecdhPubKeyBytes = Arrays.copyOfRange(payload, intLength, payload.length);
                    ECPublicKeyParameters ecdhPubKey = EccPublicKeySerializer.read(new Input(ecdhPubKeyBytes));

                    if (ecdhPubKey == null) {
                        logger2.error("Invalid decode of ecdh public key. Aborting.");
                        shutdown(registrationWrapper2, channel);

                        ReferenceCountUtil.release(message);
                        return;
                    }

                    // It is OK that we generate a new ECC keypair for ECDHE everytime that we connect. The server rotates keys every XXXX
                    // seconds, since this step is expensive.
                    metaChannel.ecdhKey = Crypto.ECC.generateKeyPair(eccSpec, new SecureRandom());

                    // register the channel!
                    try {
                        IntMap<MetaChannel> channelMap = registrationWrapper2.getAndLockChannelMap();
                        channelMap.put(metaChannel.connectionID, metaChannel);
                    } finally {
                        registrationWrapper2.releaseChannelMap();
                    }

                    metaChannel.publicKey = registration.publicKey;

                    // now save our shared AES keys
                    BasicAgreement agreement = new ECDHCBasicAgreement();
                    agreement.init(metaChannel.ecdhKey.getPrivate());
                    BigInteger shared = agreement.calculateAgreement(ecdhPubKey);

                    // now we setup our AES key based on our shared secret! (from ECDH)
                    // the shared secret is different each time a connection is made
                    byte[] keySeed = shared.toByteArray();

                    SHA384Digest sha384 = new SHA384Digest();
                    byte[] digest = new byte[sha384.getDigestSize()];
                    sha384.update(keySeed, 0, keySeed.length);
                    sha384.doFinal(digest, 0);

                    metaChannel.aesKey = Arrays.copyOfRange(digest, 0, 32); // 256bit keysize (32 bytes)
                    metaChannel.aesIV = Arrays.copyOfRange(digest, 32, 48); // 128bit blocksize (16 bytes)

                    // abort if something messed up!
                    if (metaChannel.aesKey.length != 32) {
                        logger2.error("Fatal error trying to use AES key (wrong key length).");
                        shutdown(registrationWrapper2, channel);

                        ReferenceCountUtil.release(message);
                        return;
                    }


                    Registration register = new Registration();

                    // encrypt the ECDH public key using our previous AES info
                    Output output = new Output(1024);
                    EccPublicKeySerializer.write(output, (ECPublicKeyParameters) metaChannel.ecdhKey.getPublic());
                    byte[] pubKeyAsBytes = output.toBytes();
                    register.payload = Crypto.AES.encrypt(getAesEngine(), aesKeyBytes, registration.aesIV, pubKeyAsBytes);

                    channel.writeAndFlush(register);

                    ReferenceCountUtil.release(message);
                    return;
                }

                // else, we are further along in our registration process
                // REGISTRATION CONNECTED!
                else {
                    if (metaChannel.connection == null) {
                        // STEP 1: do we have our aes keys?
                        if (metaChannel.ecdhKey != null) {
                            // wipe out our ECDH value.
                            metaChannel.ecdhKey = null;

                            // notify the client that we are ready to continue registering other session protocols (bootstraps)
                            boolean isDoneWithRegistration = registrationWrapper2.registerNextProtocol0();

                            // tell the server we are done, and to setup crypto on it's side
                            if (isDoneWithRegistration) {
                                channel.writeAndFlush(registration);

                                // re-sync the TCP delta round trip time
                                metaChannel.updateTcpRoundTripTime();
                            }

                            // if we are NOT done, then we will continue registering other protocols, so do nothing else here.
                        }
                        // we only get this when we are 100% done with the registration of all connection types.
                        else {
                            setupConnectionCrypto(metaChannel);
                            // AES ENCRYPTION NOW USED

                            // this sets up the pipeline for the client, so all the necessary handlers are ready to go
                            establishConnection(metaChannel);
                            setupConnection(metaChannel);

                            final MetaChannel metaChannel2 = metaChannel;
                            // wait for a "round trip" amount of time, then notify the APP!
                            channel.eventLoop()
                                   .schedule(new Runnable() {
                                       @Override
                                       public
                                       void run() {
                                           Logger logger2 = RegistrationRemoteHandlerClientTCP.this.logger;
                                           if (logger2.isTraceEnabled()) {
                                               logger2.trace("Notify Connection");
                                           }
                                           notifyConnection(metaChannel2);
                                       }
                                   }, metaChannel.getNanoSecBetweenTCP() * 2, TimeUnit.NANOSECONDS);
                        }
                    }
                }
            }
            else {
                // this means that UDP beat us to the "punch", and notified before we did. (notify removes all the entries from the map)
            }
        }
        else {
            logger2.error("Error registering TCP with remote server!");
            shutdown(registrationWrapper2, channel);
        }

        ReferenceCountUtil.release(message);
    }
}
