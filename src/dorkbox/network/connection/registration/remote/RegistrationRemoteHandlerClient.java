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
import java.util.LinkedList;
import java.util.List;

import javax.crypto.spec.SecretKeySpec;

import org.bouncycastle.crypto.BasicAgreement;
import org.bouncycastle.crypto.agreement.ECDHCBasicAgreement;
import org.bouncycastle.crypto.digests.SHA384Digest;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;

import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

import dorkbox.network.connection.ConnectionImpl;
import dorkbox.network.connection.RegistrationWrapperClient;
import dorkbox.network.connection.registration.MetaChannel;
import dorkbox.network.connection.registration.Registration;
import dorkbox.util.crypto.CryptoECC;
import dorkbox.util.exceptions.SecurityException;
import dorkbox.util.serialization.EccPublicKeySerializer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;

public
class RegistrationRemoteHandlerClient extends RegistrationRemoteHandler<RegistrationWrapperClient> {

    RegistrationRemoteHandlerClient(final String name, final RegistrationWrapperClient registrationWrapper, final EventLoopGroup workerEventLoop) {
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
    void readClient(final ChannelHandlerContext context, final Channel channel, final Registration registration, final String type, final MetaChannel metaChannel) {
        final InetSocketAddress remoteAddress = (InetSocketAddress) channel.remoteAddress();

        //  IN: session ID + public key + ecc parameters (which are a nonce. the SERVER defines what these are)
        // OUT: remote ECDH shared payload
        if (metaChannel.secretKey == null && registration.publicKey != null) {
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
        if (metaChannel.secretKey == null) {
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

            byte[] key = org.bouncycastle.util.Arrays.copyOfRange(digest, 0, 32); // 256bit keysize (32 bytes)
            metaChannel.secretKey = new SecretKeySpec(key, "AES");

            Registration outboundRegister = new Registration(metaChannel.sessionId);

            // do we have any more registrations?
            outboundRegister.hasMore = registrationWrapper.hasMoreRegistrations();
            if (outboundRegister.hasMore) {
                metaChannel.totalProtocols.incrementAndGet();
            }

            channel.writeAndFlush(outboundRegister);

            // wait for ack from the server before registering the next protocol
            return;
        }


        // IN: upgrade>0 if we must upgrade this connection
        // can this pipeline can now be upgraded
        int upgradeType = registration.upgradeType;
        if (upgradeType > 0) {
            // upgrade the connection to an none/compression/encrypted connection
            upgradeEncoders(upgradeType, channel, metaChannel);
            upgradeDecoders(upgradeType, channel, metaChannel);

            logChannelUpgrade(upgradeType, channel, metaChannel);
        }

        // IN: hasMore=true if we have more registrations to do, false otherwise
        if (registrationWrapper.hasMoreRegistrations()) {
            logger.trace("Starting another protocol registration");
            metaChannel.totalProtocols.incrementAndGet();
            registrationWrapper.startNextProtocolRegistration();
            return;
        }


        //
        //
        // we only get this when we are 100% done with the registration and upgrade of all connection types.
        //
        // THIS is the last channel registered!
        //



        // we don't verify anything on the CLIENT. We only verify on the server.
        // we don't support registering NEW classes after the client starts.

        // this will perform channel WRITE on whatever channel is the last channel registered!
        if (!registration.upgraded) {
            // this only get's called once. Ever other time the server talks to the client now, "upgraded" will be true.

            // this can ONLY be created when all protocols are registered!
            metaChannel.connection = this.registrationWrapper.connection0(metaChannel, remoteAddress);

            if (metaChannel.tcpChannel != null) {
                metaChannel.tcpChannel.pipeline().addLast(CONNECTION_HANDLER, metaChannel.connection);
            }
            if (metaChannel.udpChannel != null) {
                metaChannel.udpChannel.pipeline().addLast(CONNECTION_HANDLER, metaChannel.connection);
            }


            // this tells the server we are now "upgraded" and can continue
            boolean hasErrors = !registrationWrapper.initClassRegistration(channel, registration);
            if (hasErrors) {
                // abort if something messed up!
                shutdown(channel, registration.sessionID);
            }

            // the server will ping back when it is ready to connect
            return;
        }


        // NOTE: The server will ALWAYS call "onConnect" before we do!
        //  it does this via sending the client the "upgraded" signal JUST before it calls "onConnect"


        // It will upgrade the different connections INDIVIDUALLY, and whichever one arrives first will start the process
        // and the "late" message will be ignored
        if (!metaChannel.canUpgradePipeline.compareAndSet(true, false)) {
            return;
        }

        // remove ourselves from handling any more messages, because we are done.

        // since only the FIRST registration gets here, setup other ones as well (since they are no longer needed)
        if (metaChannel.tcpChannel != null) {
            // the "other" channel is the TCP channel that we have to cleanup
            metaChannel.tcpChannel.pipeline().remove(RegistrationRemoteHandlerClientTCP.class);
        }

        if (metaChannel.udpChannel != null) {
            // the "other" channel is the UDP channel that we have to cleanup
            metaChannel.udpChannel.pipeline().remove(RegistrationRemoteHandlerClientUDP.class);
        }



        // remove the ConnectionWrapper (that was used to upgrade the connection) and cleanup the pipeline
        // always wait until AFTER the server calls "onConnect", then we do this
        Runnable postConnectRunnable = new Runnable() {
            @Override
            public
            void run() {
                // this method runs after all of the channels have be correctly updated

                // get all of the out of order messages that we missed
                List<Object> messages = new LinkedList<Object>();

                if (metaChannel.tcpChannel != null) {
                    List<Object> list = getOutOfOrderMessagesAndReset(metaChannel.tcpChannel);
                    if (list != null) {
                        logger.trace("Getting deferred TCP messages: {}", list.size());
                        messages.addAll(list);
                    }
                }

                if (metaChannel.udpChannel != null) {
                    List<Object> list = getOutOfOrderMessagesAndReset(metaChannel.udpChannel);
                    if (list != null) {
                        logger.trace("Getting deferred UDP messages: {}", list.size());
                        messages.addAll(list);
                    }
                }

                // now call 'onMessage' in the connection object with our messages!
                try {
                    ConnectionImpl connection = metaChannel.connection;

                    for (Object message : messages) {
                        logger.trace("    deferred onMessage({}, {})", connection.id(), message);
                        try {
                            connection.channelRead(null, message);
                        } catch (Exception e) {
                            logger.error("Error running deferred messages!", e);
                        }
                    }

                } catch (Exception e) {
                    logger.error("Error initialising deferred messages!", e);
                }
            }
        };

        cleanupPipeline(channel, metaChannel, null, postConnectRunnable);
    }
}
