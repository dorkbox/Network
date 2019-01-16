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

import dorkbox.network.connection.RegistrationWrapper;
import dorkbox.network.connection.RegistrationWrapper.STATE;
import dorkbox.network.connection.registration.MetaChannel;
import dorkbox.network.connection.registration.Registration;
import dorkbox.util.crypto.CryptoECC;
import dorkbox.util.serialization.EccPublicKeySerializer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;

public
class RegistrationRemoteHandlerServer extends RegistrationRemoteHandler {
    private static final long ECDH_TIMEOUT = TimeUnit.MINUTES.toNanos(10L); // 10 minutes in nanoseconds

    private static final ECParameterSpec eccSpec = ECNamedCurveTable.getParameterSpec(CryptoECC.curve25519);
    private final Object ecdhKeyLock = new Object();
    private AsymmetricCipherKeyPair ecdhKeyPair;
    private volatile long ecdhTimeout = System.nanoTime();


    RegistrationRemoteHandlerServer(final String name, final RegistrationWrapper registrationWrapper, final EventLoopGroup workerEventLoop) {
        super(name, registrationWrapper, workerEventLoop);
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
        if (metaChannel.aesKey == null) {
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

            metaChannel.aesKey = Arrays.copyOfRange(digest, 0, 32); // 256bit keysize (32 bytes)
            metaChannel.aesIV = Arrays.copyOfRange(digest, 32, 44); // 96bit blocksize (12 bytes) required by AES-GCM

            if (invalidAES(metaChannel)) {
                // abort if something messed up!
                shutdown(channel, registration.sessionID);
                return;
            }

            Registration outboundRegister = new Registration(metaChannel.sessionId);

            Output output = new Output(1024);
            EccPublicKeySerializer.write(output, (ECPublicKeyParameters) metaChannel.ecdhKey.getPublic());
            outboundRegister.payload = output.toBytes();

            channel.writeAndFlush(outboundRegister);
            return;
        }

        // NOTE: if we have more registrations, we will "bounce back" that status so the client knows what to do.
        // IN: hasMore=true if we have more registrations to do, false otherwise

        // ALWAYS upgrade the connection at this point.
        // IN: upgraded=false if we haven't upgraded to encryption yet (this will always be the case right after encryption is setup)

        if (!registration.upgraded) {
            // upgrade the connection to an encrypted connection
            registration.upgrade = true;
            upgradeDecoders(channel, metaChannel);

            // bounce back to the client so it knows we received it
            channel.write(registration);

            // this pipeline encoder/decoder can now be upgraded, and the "connection" added
            upgradeEncoders(channel, metaChannel, remoteAddress);

            if (!registration.hasMore) {
                // we only get this when we are 100% done with the registration of all connection types.

                // setup the pipeline with the real connection
                upgradePipeline(metaChannel, remoteAddress);
            }

            channel.flush();
            return;
        }

        // the client will send their class registration data. VERIFY IT IS CORRECT!
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
        // we only get this when we are 100% done with the registration of all connection types.
        //      the context is the LAST protocol to be registered
        //
        //


        // remove the ConnectionWrapper (that was used to upgrade the connection) and cleanup the pipeline
        cleanupPipeline(metaChannel, new Runnable() {
            @Override
            public
            void run() {
                // this method runs after the "onConnect()" runs and only after all of the channels have be correctly updated

                // this tells the client we are ready to connect (we just bounce back the original message over ALL protocols)
                if (metaChannel.tcpChannel != null) {
                    logger.trace("Sending TCP upgraded command");
                    Registration reg = new Registration(registration.sessionID);
                    reg.upgraded = true;
                    metaChannel.tcpChannel.writeAndFlush(reg);
                }

                if (metaChannel.udpChannel != null) {
                    logger.trace("Sending UDP upgraded command");
                    Registration reg = new Registration(registration.sessionID);
                    reg.upgraded = true;
                    metaChannel.udpChannel.writeAndFlush(reg);
                }
            }
        });
    }
}
