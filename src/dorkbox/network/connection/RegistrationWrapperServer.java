package dorkbox.network.connection;

import java.net.InetSocketAddress;

import org.slf4j.Logger;

import dorkbox.network.connection.registration.MetaChannel;
import dorkbox.network.connection.registration.Registration;
import dorkbox.network.connection.registration.UpgradeType;
import dorkbox.network.serialization.Serialization;
import dorkbox.util.RandomUtil;

/**
 *
 */
public
class RegistrationWrapperServer extends RegistrationWrapper {
    public
    RegistrationWrapperServer(final EndPoint endPoint, final Logger logger) {
        super(endPoint, logger);
    }

    /**
     * MetaChannel allow access to the same "session" across TCP/UDP/etc.
     * <p>
     * The connection ID '0' is reserved to mean "no channel ID yet"
     */
    public
    MetaChannel createSession() {
        int sessionId = RandomUtil.int_();
        while (sessionId == 0 && sessionMap.containsKey(sessionId)) {
            sessionId = RandomUtil.int_();
        }

        MetaChannel metaChannel;
        synchronized (sessionMap) {
            // one final check, but slower...
            while (sessionId == 0 && sessionMap.containsKey(sessionId)) {
                sessionId = RandomUtil.int_();
            }

            metaChannel = new MetaChannel(sessionId);
            sessionMap.put(sessionId, metaChannel);


            // TODO: clean out sessions that are stale!
        }

        return metaChannel;
    }

    public
    boolean acceptRemoteConnection(final InetSocketAddress remoteAddress) {
        return ((EndPointServer) this.endPoint).acceptRemoteConnection(remoteAddress);
    }

    /**
     * Only called by the server!
     *
     * If we are loopback or the client is a specific IP/CIDR address, then we do things differently. The LOOPBACK address will never encrypt or compress the traffic.
     */
    public
    byte getConnectionUpgradeType(final InetSocketAddress remoteAddress) {
        return ((EndPointServer) this.endPoint).getConnectionUpgradeType(remoteAddress);
    }


    public
    STATE verifyClassRegistration(final MetaChannel metaChannel, final Registration registration) {
        if (registration.upgradeType == UpgradeType.FRAGMENTED) {
            byte[] fragment = registration.payload;

            // this means that the registrations are FRAGMENTED!
            // max size of ALL fragments is xxx * 127
            if (metaChannel.fragmentedRegistrationDetails == null) {
                metaChannel.remainingFragments = fragment[1];
                metaChannel.fragmentedRegistrationDetails = new byte[Serialization.CLASS_REGISTRATION_VALIDATION_FRAGMENT_SIZE * fragment[1]];
            }

            System.arraycopy(fragment, 2, metaChannel.fragmentedRegistrationDetails, fragment[0] * Serialization.CLASS_REGISTRATION_VALIDATION_FRAGMENT_SIZE, fragment.length - 2);
            metaChannel.remainingFragments--;


            if (fragment[0] + 1 == fragment[1]) {
                // this is the last fragment in the in byte array (but NOT necessarily the last fragment to arrive)
                int correctSize = (Serialization.CLASS_REGISTRATION_VALIDATION_FRAGMENT_SIZE * (fragment[1] - 1)) + (fragment.length - 2);
                byte[] correctlySized = new byte[correctSize];
                System.arraycopy(metaChannel.fragmentedRegistrationDetails, 0, correctlySized, 0, correctSize);
                metaChannel.fragmentedRegistrationDetails = correctlySized;
            }

            if (metaChannel.remainingFragments == 0) {
                // there are no more fragments available
                byte[] details = metaChannel.fragmentedRegistrationDetails;
                metaChannel.fragmentedRegistrationDetails = null;

                if (!this.endPoint.getSerialization().verifyKryoRegistration(details)) {
                    // error
                    return STATE.ERROR;
                }
            } else {
                // wait for more fragments
                return STATE.WAIT;
            }
        }
        else {
            if (!this.endPoint.getSerialization().verifyKryoRegistration(registration.payload)) {
                return STATE.ERROR;
            }
        }

        return STATE.CONTINUE;
    }
}
