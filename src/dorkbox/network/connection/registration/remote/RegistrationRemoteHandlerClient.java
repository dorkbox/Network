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
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.bouncycastle.crypto.BasicAgreement;
import org.bouncycastle.crypto.agreement.ECDHCBasicAgreement;
import org.bouncycastle.crypto.digests.SHA384Digest;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;

import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

import dorkbox.network.connection.RegistrationWrapper;
import dorkbox.network.connection.registration.MetaChannel;
import dorkbox.network.connection.registration.Registration;
import dorkbox.util.crypto.CryptoECC;
import dorkbox.util.exceptions.SecurityException;
import dorkbox.util.serialization.EccPublicKeySerializer;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;

public
class RegistrationRemoteHandlerClient extends RegistrationRemoteHandler {

    RegistrationRemoteHandlerClient(final String name, final RegistrationWrapper registrationWrapper, final EventLoopGroup workerEventLoop) {
        super(name, registrationWrapper, workerEventLoop);

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
                            //noinspection NumericCastThatLosesPrecision
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

    /**
     * @return the direction that traffic is going to this handler (" <== " or " ==> ")
     */
    @Override
    protected
    String getConnectionDirection() {
        return " ==> ";
    }


    @SuppressWarnings("Duplicates")
    void readClient(final Channel channel, final Registration registration, final String type, final MetaChannel metaChannel) {
        final InetSocketAddress remoteAddress = (InetSocketAddress) channel.remoteAddress();

        //  IN: session ID + public key + ecc parameters (which are a nonce. the SERVER defines what these are)
        // OUT: remote ECDH shared payload
        if (metaChannel.aesKey == null && registration.publicKey != null) {
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

            // It is OK that we generate a new ECC keypair for ECDHE every time that we connect from the client.
            // The server rotates keys every XXXX seconds, since this step is expensive (and the server is the 'trusted' endpoint).
            metaChannel.ecdhKey = CryptoECC.generateKeyPair(eccSpec, registrationWrapper.getSecureRandom());

            Registration outboundRegister = new Registration(metaChannel.sessionId);

            Output output = new Output(1024);
            EccPublicKeySerializer.write(output, (ECPublicKeyParameters) metaChannel.ecdhKey.getPublic());
            outboundRegister.payload = output.toBytes();

            channel.writeAndFlush(outboundRegister);
            return;
        }

        //  IN: remote ECDH shared payload
        // OUT: hasMore=true if we have more registrations to do, false otherwise
        if (metaChannel.aesKey == null) {
            /*
             * Diffie-Hellman-Merkle key exchange for the AES key
             * http://en.wikipedia.org/wiki/Diffie%E2%80%93Hellman_key_exchange
             */
            byte[] ecdhPubKeyBytes = Arrays.copyOfRange(registration.payload, 0, registration.payload.length);
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

            // do we have any more registrations?
            outboundRegister.hasMore = registrationWrapper.hasMoreRegistrations();
            channel.writeAndFlush(outboundRegister);

            // wait for ack from the server before registering the next protocol
            return;
        }


        // IN: upgrade=true if we must upgrade this connection
        if (registration.upgrade) {
            // this pipeline can now be marked to be upgraded

            // upgrade the connection to an encrypted connection
            // this pipeline encoder/decoder can now be upgraded, and the "connection" added
            upgradeEncoders(channel, metaChannel, remoteAddress);
            upgradeDecoders(channel, metaChannel);
        }

        // IN: hasMore=true if we have more registrations to do, false otherwise
        if (registration.hasMore) {
            registrationWrapper.startNextProtocolRegistration();
            return;
        }


        //
        //
        // we only get this when we are 100% done with the registration of all connection types.
        //
        //


        if (!registration.upgraded) {
            // setup the pipeline with the real connection
            upgradePipeline(metaChannel, remoteAddress);

            // tell the server we are upgraded (it will bounce back telling us to connect)
            registration.upgraded = true;
            channel.writeAndFlush(registration);
            return;
        }


        // remove the ConnectionWrapper (that was used to upgrade the connection)
        cleanupPipeline(metaChannel);

        workerEventLoop.schedule(new Runnable() {
            @Override
            public
            void run() {
                logger.trace("Notify Connection");
                doConnect(metaChannel);
            }
        }, 20, TimeUnit.MILLISECONDS);
    }
}
