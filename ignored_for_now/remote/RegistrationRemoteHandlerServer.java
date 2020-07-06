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

import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

import javax.crypto.spec.SecretKeySpec;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.BasicAgreement;
import org.bouncycastle.crypto.agreement.ECDHCBasicAgreement;
import org.bouncycastle.crypto.digests.SHA384Digest;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECParameterSpec;
import org.bouncycastle.util.Arrays;

import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

import dorkbox.network.connection.EndPoint;
import dorkbox.network.connection.RegistrationWrapper.STATE;
import dorkbox.network.connection.RegistrationWrapperServer;
import dorkbox.network.connection.registration.MetaChannel;
import dorkbox.network.connection.registration.Registration;
import dorkbox.util.crypto.CryptoECC;
import dorkbox.util.serialization.EccPublicKeySerializer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;

public
class RegistrationRemoteHandlerServer extends RegistrationRemoteHandler<RegistrationWrapperServer> {
    private static final long ECDH_TIMEOUT = TimeUnit.MINUTES.toNanos(10L); // 10 minutes in nanoseconds

    private static final ECParameterSpec eccSpec = ECNamedCurveTable.getParameterSpec(CryptoECC.curve25519);
    private final Object ecdhKeyLock = new Object();
    private AsymmetricCipherKeyPair ecdhKeyPair;
    private volatile long ecdhTimeout = System.nanoTime();


    RegistrationRemoteHandlerServer(final String name, final RegistrationWrapperServer registrationWrapper, final EventLoopGroup workerEventLoop) {
        super(name, registrationWrapper, workerEventLoop);
    }

    /**
     * STEP 1: Channel is first created
     */
    @Override
    protected
    void initChannel(final Channel channel) {
        // check to see if this connection is permitted.
        final InetSocketAddress remoteAddress = (InetSocketAddress) channel.remoteAddress();
        if (!registrationWrapper.acceptRemoteConnection(remoteAddress)) {
            StringBuilder stringBuilder = new StringBuilder();
            EndPoint.Companion.getHostDetails(stringBuilder, remoteAddress);

            logger.error("Remote connection [{}] is not permitted! Aborting connection process.", stringBuilder.toString());
            shutdown(channel, 0);
            return;
        }

        super.initChannel(channel);
    }


    /**
     * @return the direction that traffic is going to this handler (" <== " or " ==> ")
     */
    @Override
    protected
    String getConnectionDirection() {
        return " <== ";
    }


    /**
     * Rotates the ECDH key every 10 minutes, as this is a VERY expensive calculation to keep on doing for every connection.
     */
    private
    AsymmetricCipherKeyPair getEchdKeyOnRotate(final SecureRandom secureRandom) {
        if (this.ecdhKeyPair == null || System.nanoTime() - this.ecdhTimeout > ECDH_TIMEOUT) {
            synchronized (this.ecdhKeyLock) {
                this.ecdhTimeout = System.nanoTime();
                this.ecdhKeyPair = CryptoECC.generateKeyPair(eccSpec, secureRandom);
            }
        }

        return this.ecdhKeyPair;
    }

    /*
     * UDP has a VERY limited size, so we have to break up registration steps into the following
     * 1) session ID == 0 -> exchange session ID and public keys (session ID != 0 now)
     * 2) session ID != 0 -> establish ECDH shared secret as AES key/iv
     */
    @SuppressWarnings("Duplicates")
    void readServer(final ChannelHandlerContext context, final Channel channel, final Registration registration, final String type, final MetaChannel metaChannel) {
        final InetSocketAddress remoteAddress = (InetSocketAddress) channel.remoteAddress();

        //  IN: session ID == 0 (start of new connection)
        // OUT: session ID + public key + ecc parameters (which are a nonce. the SERVER defines what these are)
        if (registration.sessionID == 0) {
            // whoa! Didn't send valid public key info!
            if (invalidPublicKey(registration, type)) {
                shutdown(channel, registration.sessionID);
                return;
            }

            // want to validate the public key used! This is similar to how SSH works, in that once we use a public key, we want to validate
            // against that ip-address::key pair, so we can better protect against MITM/spoof attacks.
            if (invalidRemoteAddress(metaChannel, registration, type, remoteAddress)) {
                // whoa! abort since something messed up! (log and recording if key changed happens inside of validate method)
                shutdown(channel, registration.sessionID);
                return;
            }

            // save off remote public key. This is ALWAYS the same, where the ECDH changes every time...
            metaChannel.publicKey = registration.publicKey;

            // tell the client to continue it's registration process.
            Registration outboundRegister = new Registration(metaChannel.sessionId);
            outboundRegister.publicKey = registrationWrapper.getPublicKey();
            outboundRegister.eccParameters = CryptoECC.generateSharedParameters(registrationWrapper.getSecureRandom());

            channel.writeAndFlush(outboundRegister);
            return;
        }


        //  IN: remote ECDH shared payload
        // OUT: server ECDH shared payload
        if (metaChannel.secretKey == null) {
            /*
             * Diffie-Hellman-Merkle key exchange for the AES key
             * http://en.wikipedia.org/wiki/Diffie%E2%80%93Hellman_key_exchange
             */

            // the ECDH key will ROTATE every 10 minutes, since generating it for EVERY connection is expensive
            // and since we are combining ECDHE+ECC public/private keys for each connection, other
            // connections cannot break someone else's connection, since they are still protected by their own private keys.
            metaChannel.ecdhKey = getEchdKeyOnRotate(registrationWrapper.getSecureRandom());

            byte[] ecdhPubKeyBytes = java.util.Arrays.copyOfRange(registration.payload, 0, registration.payload.length);
            ECPublicKeyParameters ecdhPubKey;
            try {
                ecdhPubKey = EccPublicKeySerializer.read(new Input(ecdhPubKeyBytes));
            } catch (KryoException e) {
                logger.error("Invalid decode of ECDH public key. Aborting.");
                shutdown(channel, registration.sessionID);
                return;
            }

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

            byte[] key = Arrays.copyOfRange(digest, 0, 32); // 256bit keysize (32 bytes)
            metaChannel.secretKey = new SecretKeySpec(key, "AES");

            Registration outboundRegister = new Registration(metaChannel.sessionId);

            Output output = new Output(1024);
            EccPublicKeySerializer.write(output, (ECPublicKeyParameters) metaChannel.ecdhKey.getPublic());
            outboundRegister.payload = output.toBytes();

            channel.writeAndFlush(outboundRegister);
            return;
        }

        // NOTE: if we have more registrations, we will "bounce back" that status so the client knows what to do.
        // IN: hasMore=true if we have more registrations to do, false otherwise

        // Some cases we want to SKIP encryption (ie, loopback or specific IP/CIDR addresses)
        //     OTHERWISE ALWAYS upgrade the connection at this point.
        // IN: upgraded=false if we haven't upgraded to encryption yet (this will always be the case right after encryption is setup)

        if (!registration.upgraded) {
            // this is the last protocol registered
            if (!registration.hasMore) {
                // this can ONLY be created when all protocols are registered!
                // this must happen before we verify class registrations.
                metaChannel.connection = this.registrationWrapper.connection0(metaChannel, remoteAddress);

                if (metaChannel.tcpChannel != null) {
                    // metaChannel.tcpChannel.pipeline().addLast(CONNECTION_HANDLER, metaChannel.connection);
                }
                if (metaChannel.udpChannel != null) {
                    // metaChannel.udpChannel.pipeline().addLast(CONNECTION_HANDLER, metaChannel.connection);
                }
            }

            // If we are loopback or the client is a specific IP/CIDR address, then we do things differently. The LOOPBACK address will never encrypt or compress the traffic.
            byte upgradeType = registrationWrapper.getConnectionUpgradeType(remoteAddress);
            registration.upgradeType = upgradeType;

            // upgrade the connection to a none/compression/encrypted connection
            upgradeDecoders(upgradeType, channel, metaChannel);

            // bounce back to the client so it knows we received it
            channel.write(registration);

            upgradeEncoders(upgradeType, channel, metaChannel);

            logChannelUpgrade(upgradeType, channel, metaChannel);

            channel.flush();
            return;
        }

        //
        //
        // we only get this when we are 100% done with encrypting/etc the connections
        //
        //


        // upgraded=true when the client will send their class registration data. VERIFY IT IS CORRECT!
        STATE state = registrationWrapper.verifyClassRegistration(metaChannel, registration);
        if (state == STATE.ERROR) {
            // abort! There was an error
            shutdown(channel, registration.sessionID);
            return;
        }
        else if (state == STATE.WAIT) {
            return;
        }
        // else, continue.



        //
        //
        // we only get this when we are 100% done with validation of class registrations. The last protocol to register gets us here.
        //
        //



        // remove ourselves from handling any more messages, because we are done.
        ChannelHandler handler = context.handler();
        channel.pipeline().remove(handler);


        // since only the LAST registration gets here, setup other ones as well (since they are no longer needed)
        if (channel == metaChannel.tcpChannel && metaChannel.udpChannel != null) {
            // the "other" channel is the UDP channel that we have to cleanup
            metaChannel.udpChannel.pipeline().remove(RegistrationRemoteHandlerServerUDP.class);
        }
        else if (channel == metaChannel.udpChannel && metaChannel.tcpChannel != null) {
            // the "other" channel is the TCP channel that we have to cleanup
            metaChannel.tcpChannel.pipeline().remove(RegistrationRemoteHandlerServerTCP.class);
        }



        // remove the ConnectionWrapper (that was used to upgrade the connection) and cleanup the pipeline
        Runnable preConnectRunnable = new Runnable() {
            @Override
            public
            void run() {
                // this method BEFORE the "onConnect()" runs and only after all of the channels have be correctly updated

                // this tells the client we are ready to connect (we just bounce back "upgraded" over TCP, preferably).
                // only the FIRST one to arrive at the client will actually setup the pipeline
                Registration reg = new Registration(registration.sessionID);
                reg.upgraded = true;

                // there is a risk of UDP losing the packet, so if we can send via TCP, then we do so.
                if (metaChannel.tcpChannel != null) {
                    logger.trace("Sending TCP upgraded command");
                    metaChannel.tcpChannel.writeAndFlush(reg);
                }
                else if (metaChannel.udpChannel != null) {
                    logger.trace("Sending UDP upgraded command");
                    metaChannel.udpChannel.writeAndFlush(reg);
                }
                else {
                    logger.error("This shouldn't happen!");

                }
            }
        };

        cleanupPipeline(channel, metaChannel, preConnectRunnable, null);
    }
}
