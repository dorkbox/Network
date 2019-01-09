package dorkbox.network.rmi.multiJVM;

import dorkbox.network.Server;
import dorkbox.network.rmi.RmiTest;
import dorkbox.network.rmi.TestCow;
import dorkbox.network.rmi.TestCowImpl;
import dorkbox.network.serialization.Serialization;
import dorkbox.util.exceptions.SecurityException;

/**
 *
 */
public
class TestServer
{
    public static
    void main(String[] args) {
        TestClient.setup();

        dorkbox.network.Configuration configuration = new dorkbox.network.Configuration();
        configuration.tcpPort = 2000;
        configuration.udpPort = 2001;

        configuration.serialization = Serialization.DEFAULT();
        RmiTest.register(configuration.serialization);

        configuration.serialization.registerRmi(TestCow.class, TestCowImpl.class);

        Server server = null;
        try {
            server = new Server(configuration);
            server.disableRemoteKeyValidation();
        } catch (SecurityException e) {
            e.printStackTrace();
        }

        server.setIdleTimeout(0);
        server.bind(true);
    }}
