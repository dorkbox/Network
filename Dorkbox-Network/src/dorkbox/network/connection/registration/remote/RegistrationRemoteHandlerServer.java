package dorkbox.network.connection.registration.remote;

import dorkbox.network.connection.RegistrationWrapper;
import dorkbox.network.connection.registration.MetaChannel;
import dorkbox.network.util.SerializationManager;

public class RegistrationRemoteHandlerServer extends RegistrationRemoteHandler {

    public RegistrationRemoteHandlerServer(String name, RegistrationWrapper registrationWrapper, SerializationManager serializationManager) {
        super(name, registrationWrapper, serializationManager);
    }

    /**
     * Registers the metachannel for the UDP server (For the TCP/UDT streams)
     */
    @Override
    protected void setupServerUdpConnection(MetaChannel metaChannel) {
        registrationWrapper.registerServerUDP(metaChannel);
    }


    /**
     * @return the direction that traffic is going to this handler (" <== " or " ==> ")
     */
    @Override
    protected String getConnectionDirection() {
        return " <== ";
    }
}