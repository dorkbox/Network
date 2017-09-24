package dorkbox.network.rmi.multiJVM;

import java.io.IOException;

import dorkbox.network.Server;
import dorkbox.network.connection.CryptoSerializationManager;
import dorkbox.network.rmi.RmiTest;
import dorkbox.network.rmi.TestObject;
import dorkbox.network.rmi.TestObjectImpl;
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

        configuration.serialization = CryptoSerializationManager.DEFAULT();
        RmiTest.register(configuration.serialization);
        configuration.serialization.registerRmiImplementation(TestObject.class, TestObjectImpl.class);

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

        server.setIdleTimeout(0);
        server.bind(true);


        // configuration.host = "localhost";
        // configuration.serialization.register(TestObjImpl.class);
        //
        // Client client = null;
        // try {
        //     client = new Client(configuration);
        // } catch (InitializationException e) {
        //     e.printStackTrace();
        // } catch (SecurityException e) {
        //     e.printStackTrace();
        // } catch (IOException e) {
        //     e.printStackTrace();
        // }
        // client.setIdleTimeout(0);
        //
        // client.listeners()
        //       .add(new dorkbox.network.connection.Listener.OnConnected<Connection>() {
        //           @Override
        //           public
        //           void connected(final Connection connection) {
        //               System.err.println("CONNECTED!");
        //
        //               try {
        //                   TestObject object = connection.createProxyObject(TestObject.class);
        //                   object.test();
        //               } catch (IOException e) {
        //                   e.printStackTrace();
        //               }
        //           }
        //       });
        //
        // try {
        //     client.connect(5000);
        // } catch (IOException e) {
        //     e.printStackTrace();
        // }
    }}
