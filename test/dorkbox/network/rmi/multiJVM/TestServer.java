package dorkbox.network.rmi.multiJVM;

import java.io.IOException;

import dorkbox.network.Server;
import dorkbox.network.rmi.RmiTest;
import dorkbox.network.rmi.TestCow;
import dorkbox.network.rmi.TestCowImpl;
import dorkbox.network.serialization.SerializationManager;
import dorkbox.util.exceptions.InitializationException;
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

        configuration.serialization = SerializationManager.DEFAULT();
        RmiTest.register(configuration.serialization);
        configuration.serialization.registerRmiImplementation(TestCow.class, TestCowImpl.class);

        Server server = null;
        try {
            server = new Server(configuration);
        } catch (InitializationException e) {
            e.printStackTrace();
        } catch (SecurityException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        // server.setIdleTimeout(0);
        server.bind(true);
    }}
