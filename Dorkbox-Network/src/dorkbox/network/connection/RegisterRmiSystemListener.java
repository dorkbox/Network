package dorkbox.network.connection;


import dorkbox.network.rmi.RmiRegistration;

class RegisterRmiSystemListener extends ListenerRaw<ConnectionImpl, RmiRegistration> {

    RegisterRmiSystemListener() {
    }

    @Override
    public void received(ConnectionImpl connection, RmiRegistration registerClass) {
        // register this into the RmiBridge
        connection.registerInternal(connection, registerClass);
    }
}
