package dorkbox.network.connection.registration.remote;

import dorkbox.network.connection.RegistrationWrapper;
import dorkbox.network.util.CryptoSerializationManager;

public class RegistrationRemoteHandlerClient extends RegistrationRemoteHandler {

    public RegistrationRemoteHandlerClient(String name, RegistrationWrapper registrationWrapper, CryptoSerializationManager serializationManager) {
        super(name, registrationWrapper, serializationManager);
    }

    /**
     * @return the direction that traffic is going to this handler (" <== " or " ==> ")
     */
    @Override
    protected String getConnectionDirection() {
        return " ==> ";
    }
}
