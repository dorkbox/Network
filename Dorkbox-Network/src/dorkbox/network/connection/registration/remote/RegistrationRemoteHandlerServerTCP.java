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

import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import dorkbox.network.connection.Connection;
import dorkbox.network.connection.RegistrationWrapper;
import dorkbox.network.connection.registration.MetaChannel;
import dorkbox.network.connection.registration.Registration;
import dorkbox.network.util.CryptoSerializationManager;
import dorkbox.util.MathUtil;
import dorkbox.util.bytes.OptimizeUtilsByteArray;
import dorkbox.util.collections.IntMap;
import dorkbox.util.crypto.CryptoAES;
import dorkbox.util.crypto.CryptoECC;
import dorkbox.util.serialization.EccPublicKeySerializer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ReferenceCountUtil;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.BasicAgreement;
import org.bouncycastle.crypto.agreement.ECDHCBasicAgreement;
import org.bouncycastle.crypto.digests.SHA384Digest;
import org.bouncycastle.crypto.engines.IESEngine;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECParameterSpec;
import org.bouncycastle.util.Arrays;
import org.slf4j.Logger;

import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

public
class RegistrationRemoteHandlerServerTCP<C extends Connection> extends RegistrationRemoteHandlerServer<C> {

    private static final long ECDH_TIMEOUT = 10L * 60L * 60L * 1000L * 1000L * 1000L; // 10 minutes in nanoseconds

    private static final ECParameterSpec eccSpec = ECNamedCurveTable.getParameterSpec(CryptoECC.p521_curve);
    private final Object ecdhKeyLock = new Object();
    private final ThreadLocal<IESEngine> eccEngineLocal = new ThreadLocal<IESEngine>();
    private AsymmetricCipherKeyPair ecdhKeyPair = CryptoECC.generateKeyPair(eccSpec, new SecureRandom());
    private volatile long ecdhTimeout = System.nanoTime();


    public
    RegistrationRemoteHandlerServerTCP(final String name,
                                       final RegistrationWrapper<C> registrationWrapper,
                                       final CryptoSerializationManager serializationManager) {
        super(name, registrationWrapper, serializationManager);
    }

    private
    IESEngine getEccEngine() {
        IESEngine iesEngine = this.eccEngineLocal.get();
        if (iesEngine == null) {
            iesEngine = CryptoECC.createEngine();
            this.eccEngineLocal.set(iesEngine);
        }
        return iesEngine;
    }

    /**
     * Rotates the ECDH key every 10 minutes, as this is a VERY expensive calculation to keep on doing for every connection.
     */
    private
    AsymmetricCipherKeyPair getEchdKeyOnRotate(final SecureRandom secureRandom) {
        if (System.nanoTime() - this.ecdhTimeout > ECDH_TIMEOUT) {
            synchronized (this.ecdhKeyLock) {
                this.ecdhTimeout = System.nanoTime();
                this.ecdhKeyPair = CryptoECC.generateKeyPair(eccSpec, secureRandom);
            }
        }

        return this.ecdhKeyPair;
    }

    /**
     * STEP 1: Channel is first created (This is TCP/UDT only, as such it differs from the client which is TCP/UDP)
     */
    @Override
    protected
    void initChannel(Channel channel) {
        super.initChannel(channel);
    }

    /**
     * STEP 2: Channel is now active. Prepare the meta channel to listen for the registration process
     */
    @Override
    public
    void channelActive(ChannelHandlerContext context) throws Exception {
        super.channelActive(context);

        Channel channel = context.channel();

        // The ORDER has to be TCP (always) -> UDP (optional, in UDP listener) -> UDT (optional)
        // TCP
        // save this new connection in our associated map. We will get a new one for each new connection from a client.
        MetaChannel metaChannel = new MetaChannel();
        metaChannel.tcpChannel = channel;

        try {
            IntMap<MetaChannel> channelMap = this.registrationWrapper.getAndLockChannelMap();
            channelMap.put(channel.hashCode(), metaChannel);
        } finally {
            this.registrationWrapper.releaseChannelMap();
        }

        Logger logger2 = this.logger;
        if (logger2.isTraceEnabled()) {
            logger2.trace(this.name, "New TCP connection. Saving TCP channel info.");
        }
    }

    /**
     * STEP 3-XXXXX: We pass registration messages around until we the registration handshake is complete!
     */
    @Override
    public
    void channelRead(ChannelHandlerContext context, Object message) throws Exception {
        Channel channel = context.channel();

        // only TCP will come across here for the server. (UDP here is called by the UDP handler/wrapper)

        RegistrationWrapper registrationWrapper2 = this.registrationWrapper;
        if (message instanceof Registration) {
            Registration registration = (Registration) message;

            MetaChannel metaChannel = null;
            try {
                IntMap<MetaChannel> channelMap = registrationWrapper2.getAndLockChannelMap();
                metaChannel = channelMap.get(channel.hashCode());
            } finally {
                registrationWrapper2.releaseChannelMap();
            }

            // make sure this connection was properly registered in the map. (IT SHOULD BE)
            Logger logger2 = this.logger;
            if (metaChannel != null) {
                metaChannel.updateTcpRoundTripTime();
                SecureRandom secureRandom = registrationWrapper2.getSecureRandom();

                // first time we've seen data from this new TCP connection
                if (metaChannel.connectionID == null) {
                    // whoa! Didn't send valid public key info!
                    if (registration.publicKey == null) {
                        logger2.error("Null ECC public key during client handshake. This shouldn't happen!");
                        shutdown(registrationWrapper2, channel);

                        ReferenceCountUtil.release(message);
                        return;
                    }

                    // want to validate the public key used! This is similar to how SSH works, in that once we use a public key, we want to validate
                    // against that ip-address::key pair, so we can better protect against MITM/spoof attacks.
                    InetSocketAddress tcpRemoteClient = (InetSocketAddress) channel.remoteAddress();

                    boolean valid = registrationWrapper2.validateRemoteServerAddress(tcpRemoteClient, registration.publicKey);

                    if (!valid) {
                        //whoa! abort since something messed up! (log happens inside of validate method)
                        if (logger2.isInfoEnabled()) {
                            logger2.info("Invalid ECC public key for IP {} during handshake with client. Toggling extra flag in channel to indicate this.",
                                         tcpRemoteClient.getAddress()
                                                        .getHostAddress());
                        }
                        metaChannel.changedRemoteKey = true;
                    }


                    Integer connectionID = MathUtil.randomInt();
                    // if I'm unlucky, keep from confusing connections!

                    try {
                        IntMap<MetaChannel> channelMap = registrationWrapper2.getAndLockChannelMap();
                        while (channelMap.containsKey(connectionID)) {
                            connectionID = MathUtil.randomInt();
                        }

                        metaChannel.connectionID = connectionID;
                        channelMap.put(connectionID, metaChannel);

                    } finally {
                        registrationWrapper2.releaseChannelMap();
                    }

                    Registration register = new Registration();

                    // save off encryption handshake info
                    metaChannel.publicKey = registration.publicKey;

                    OptimizeUtilsByteArray optimizeUtils = OptimizeUtilsByteArray.get();
                    // use ECC to create an AES key, which is used to encrypt the ECDH public key and the connectionID

                    /*
                     * Diffie-Hellman-Merkle key
                     * see http://en.wikipedia.org/wiki/Diffie%E2%80%93Hellman_key_exchange
                     */

                    // the ecdh key will ROTATE every 10 minutes, since generating it for EVERY connection is expensive
                    // and since we are combining ECDHE+ECC public/private keys for each connection, other
                    // connections cannot break someone else's connection, since they are still protected by their own private keys.
                    metaChannel.ecdhKey = getEchdKeyOnRotate(secureRandom);
                    Output output = new Output(1024);
                    EccPublicKeySerializer.write(output, (ECPublicKeyParameters) metaChannel.ecdhKey.getPublic());
                    byte[] pubKeyAsBytes = output.toBytes();

                    // save off the connectionID as a byte array

                    int intLength = optimizeUtils.intLength(connectionID, true);
                    byte[] idAsBytes = new byte[intLength];
                    optimizeUtils.writeInt(idAsBytes, connectionID, true);

                    byte[] combinedBytes = Arrays.concatenate(idAsBytes, pubKeyAsBytes);


                    // now we have to setup the TEMP AES key!
                    metaChannel.aesKey = new byte[32]; // 256bit keysize (32 bytes)
                    metaChannel.aesIV = new byte[16]; // 128bit blocksize (16 bytes)
                    secureRandom.nextBytes(metaChannel.aesKey);
                    secureRandom.nextBytes(metaChannel.aesIV);

                    IESEngine encrypt = getEccEngine();

                    register.publicKey = registrationWrapper2.getPublicKey();
                    register.eccParameters = CryptoECC.generateSharedParameters(secureRandom);

                    // now we have to ENCRYPT the AES key!
                    register.eccParameters = CryptoECC.generateSharedParameters(secureRandom);
                    register.aesIV = metaChannel.aesIV;
                    register.aesKey = CryptoECC.encrypt(encrypt,
                                                         registrationWrapper2.getPrivateKey(),
                                                         metaChannel.publicKey,
                                                         register.eccParameters,
                                                         metaChannel.aesKey,
                                                         logger);


                    // now encrypt payload via AES
                    register.payload = CryptoAES.encrypt(getAesEngine(), metaChannel.aesKey, register.aesIV, combinedBytes, logger);

                    channel.writeAndFlush(register);

                    if (logger2.isTraceEnabled()) {
                        logger2.trace("Assigning new random connection ID for TCP and performing ECDH.");
                    }

                    // re-sync the TCP delta round trip time
                    metaChannel.updateTcpRoundTripTime();

                    ReferenceCountUtil.release(message);
                    return;
                }

                // else continue the registration process
                else {
                    // do we have a connection setup yet?
                    if (metaChannel.connection == null) {
                        // check if we have ECDH specified (if we do, then we are at STEP 1).
                        if (metaChannel.ecdhKey != null) {
                            // now we have to decrypt the ECDH key using our TEMP AES keys

                            byte[] payload = CryptoAES.decrypt(getAesEngine(),
                                                                metaChannel.aesKey,
                                                                metaChannel.aesIV,
                                                                registration.payload,
                                                                logger);

                            if (payload.length == 0) {
                                logger2.error("Invalid decryption of payload. Aborting.");
                                shutdown(registrationWrapper2, channel);

                                ReferenceCountUtil.release(message);
                                return;
                            }

                            ECPublicKeyParameters ecdhPubKey = EccPublicKeySerializer.read(new Input(payload));

                            if (ecdhPubKey == null) {
                                logger2.error("Invalid decode of ecdh public key. Aborting.");
                                shutdown(registrationWrapper2, channel);

                                ReferenceCountUtil.release(message);
                                return;
                            }

                            BasicAgreement agreement = new ECDHCBasicAgreement();
                            agreement.init(metaChannel.ecdhKey.getPrivate());
                            BigInteger shared = agreement.calculateAgreement(ecdhPubKey);

                            // wipe out our saved values.
                            metaChannel.aesKey = null;
                            metaChannel.aesIV = null;
                            metaChannel.ecdhKey = null;

                            // now we setup our AES key based on our shared secret! (from ECDH)
                            // the shared secret is different each time a connection is made
                            byte[] keySeed = shared.toByteArray();

                            SHA384Digest sha384 = new SHA384Digest();
                            byte[] digest = new byte[sha384.getDigestSize()];
                            sha384.update(keySeed, 0, keySeed.length);
                            sha384.doFinal(digest, 0);

                            metaChannel.aesKey = Arrays.copyOfRange(digest, 0, 32); // 256bit keysize (32 bytes)
                            metaChannel.aesIV = Arrays.copyOfRange(digest, 32, 48); // 128bit blocksize (16 bytes)

                            // tell the client to continue it's registration process.
                            channel.writeAndFlush(new Registration());
                        }

                        // we only get this when we are 100% done with the registration of all connection types.
                        else {
                            channel.writeAndFlush(registration); // causes client to setup network connection & AES

                            setupConnectionCrypto(metaChannel);
                            // AES ENCRPYTION NOW USED

                            // this sets up the pipeline for the server, so all the necessary handlers are ready to go
                            establishConnection(metaChannel);
                            setupConnection(metaChannel);

                            final MetaChannel chan2 = metaChannel;
                            // wait for a "round trip" amount of time, then notify the APP!
                            channel.eventLoop()
                                   .schedule(new Runnable() {
                                       @Override
                                       public
                                       void run() {
                                           Logger logger2 = RegistrationRemoteHandlerServerTCP.this.logger;
                                           if (logger2.isTraceEnabled()) {
                                               logger2.trace("Notify Connection");
                                           }
                                           notifyConnection(chan2);
                                       }
                                   }, metaChannel.getNanoSecBetweenTCP() * 2, TimeUnit.NANOSECONDS);
                        }
                    }

                    ReferenceCountUtil.release(message);
                    return;
                }
            }
            // this should NEVER happen!
            logger2.error("Error registering TCP channel! MetaChannel is null!");
        }

        shutdown(registrationWrapper2, channel);
        ReferenceCountUtil.release(message);
    }
}
