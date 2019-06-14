package dorkbox.network.connection;

import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.slf4j.Logger;

import dorkbox.network.connection.registration.MetaChannel;
import dorkbox.network.connection.registration.Registration;
import dorkbox.network.connection.registration.UpgradeType;
import dorkbox.network.serialization.Serialization;
import dorkbox.util.collections.IntMap.Values;
import dorkbox.util.exceptions.SecurityException;
import io.netty.channel.Channel;

/**
 *
 */
public
class RegistrationWrapperClient extends RegistrationWrapper {
    public
    RegistrationWrapperClient(final EndPoint endPoint, final Logger logger) {
        super(endPoint, logger);
    }

    /**
     * MetaChannel allow access to the same "session" across TCP/UDP/etc
     * <p>
     * The connection ID '0' is reserved to mean "no channel ID yet"
     */
    public
    MetaChannel createSession(int sessionId) {
        MetaChannel metaChannel = new MetaChannel(sessionId);
        sessionMap.put(sessionId, metaChannel);

        return metaChannel;
    }

    /**
     * @return the first session we have available. This is for the CLIENT to track sessions (between TCP/UDP) to a server
     */
    public MetaChannel getFirstSession() {
        Values<MetaChannel> values = sessionMap.values();
        if (values.hasNext) {
            return values.next();
        }
        return null;
    }

    public
    boolean isClient() {
        return true;
    }

    /**
     * Internal call by the pipeline to check if the client has more protocol registrations to complete.
     *
     * @return true if there are more registrations to process, false if we are 100% done with all types to register (TCP/UDP/etc)
     */
    public
    boolean hasMoreRegistrations() {
        return this.endPoint.hasMoreRegistrations();
    }

    /**
     * Internal call by the pipeline to notify the client to continue registering the different session protocols. The server does not use
     * this.
     */
    public
    void startNextProtocolRegistration() {
        this.endPoint.startNextProtocolRegistration();
    }

    public
    void removeRegisteredServerKey(final byte[] hostAddress) throws SecurityException {
        ECPublicKeyParameters savedPublicKey = this.endPoint.propertyStore.getRegisteredServerKey(hostAddress);
        if (savedPublicKey != null) {
            Logger logger2 = this.logger;
            if (logger2.isDebugEnabled()) {
                logger2.debug("Deleting remote IP address key {}.{}.{}.{}",
                              hostAddress[0],
                              hostAddress[1],
                              hostAddress[2],
                              hostAddress[3]);
            }

            this.endPoint.propertyStore.removeRegisteredServerKey(hostAddress);
        }
    }

    public
    boolean initClassRegistration(final Channel channel, final Registration registration) {
        byte[] details = this.endPoint.getSerialization().getKryoRegistrationDetails();

        int length = details.length;
        if (length > Serialization.CLASS_REGISTRATION_VALIDATION_FRAGMENT_SIZE) {
            // it is too large to send in a single packet

            // child arrays have index 0 also as their 'index' and 1 is the total number of fragments
            byte[][] fragments = divideArray(details, Serialization.CLASS_REGISTRATION_VALIDATION_FRAGMENT_SIZE);
            if (fragments == null) {
                logger.error("Too many classes have been registered for Serialization. Please report this issue");

                return false;
            }

            int allButLast = fragments.length - 1;

            for (int i = 0; i < allButLast; i++) {
                final byte[] fragment = fragments[i];
                Registration fragmentedRegistration = new Registration(registration.sessionID);
                fragmentedRegistration.payload = fragment;

                // tell the server we are fragmented
                fragmentedRegistration.upgradeType = UpgradeType.FRAGMENTED;

                // tell the server we are upgraded (it will bounce back telling us to connect)
                fragmentedRegistration.upgraded = true;
                channel.writeAndFlush(fragmentedRegistration);
            }

            // now tell the server we are done with the fragments
            Registration fragmentedRegistration = new Registration(registration.sessionID);
            fragmentedRegistration.payload = fragments[allButLast];

            // tell the server we are fragmented
            fragmentedRegistration.upgradeType = UpgradeType.FRAGMENTED;

            // tell the server we are upgraded (it will bounce back telling us to connect)
            fragmentedRegistration.upgraded = true;
            channel.writeAndFlush(fragmentedRegistration);
        } else {
            registration.payload = details;

            // tell the server we are upgraded (it will bounce back telling us to connect)
            registration.upgraded = true;
            channel.writeAndFlush(registration);
        }

        return true;
    }

    /**
     * Split array into chunks, max of 256 chunks.
     * byte[0] = chunk ID
     * byte[1] = total chunks (0-255) (where 0->1, 2->3, 127->127 because this is indexed by a byte)
     */
    private static
    byte[][] divideArray(byte[] source, int chunksize) {

        int fragments = (int) Math.ceil(source.length / ((double) chunksize + 2));
        if (fragments > 127) {
            // cannot allow more than 127
            return null;
        }

        // pre-allocate the memory
        byte[][] splitArray = new byte[fragments][chunksize + 2];
        int start = 0;

        for (int i = 0; i < splitArray.length; i++) {
            int length;

            if (start + chunksize > source.length) {
                length = source.length - start;
            }
            else {
                length = chunksize;
            }
            splitArray[i] = new byte[length+2];
            splitArray[i][0] = (byte) i;
            splitArray[i][1] = (byte) fragments;
            System.arraycopy(source, start, splitArray[i], 2, length);

            start += chunksize;
        }

        return splitArray;
    }
}
