
package dorkbox.network.rmi;

import static org.junit.Assert.assertEquals;

import java.io.IOException;

import org.junit.Test;

import dorkbox.network.Client;
import dorkbox.network.ConnectionOptions;
import dorkbox.network.BaseTest;
import dorkbox.network.Server;
import dorkbox.network.connection.Connection;
import dorkbox.network.connection.ListenerRaw;
import dorkbox.network.util.SerializationManager;
import dorkbox.network.util.exceptions.InitializationException;
import dorkbox.network.util.exceptions.SecurityException;

public class RmiSendObjectTest extends BaseTest {
    private RmiBridge serverRMI;

    /**
     * In this test the server has two objects in an object space. The client
     * uses the first remote object to get the second remote object.
     */
    @Test
    public void rmi() throws IOException, InitializationException, SecurityException {
        ConnectionOptions connectionOptions = new ConnectionOptions();
        connectionOptions.tcpPort = tcpPort;
        connectionOptions.host = host;

        Server server = new Server(connectionOptions);
        SerializationManager serverSer = server.getSerialization();
        register(serverSer);
        addEndPoint(server);
        server.bind(false);


        // After all common registrations, register OtherObjectImpl only on the server using the remote object interface ID.
        // This causes OtherObjectImpl to be serialized as OtherObject.
        int otherObjectID = serverSer.getRegistration(OtherObject.class).getId();
        serverSer.register(OtherObjectImpl.class, new RemoteObjectSerializer<OtherObjectImpl>(), otherObjectID);


        // TestObjectImpl has a reference to an OtherObjectImpl.
        final TestObjectImpl serverTestObject = new TestObjectImpl();
        serverTestObject.otherObject = new OtherObjectImpl();

        // Both objects must be registered with the ObjectSpace.
        serverRMI = server.getRmiBridge();
        serverRMI.register(42, serverTestObject);
        serverRMI.register(777, serverTestObject.getOtherObject());

        server.listeners().add(new ListenerRaw<Connection, OtherObjectImpl>() {
            @Override
            public void connected(final Connection connection) {
                // Allow the connection to access objects in the ObjectSpace.
                serverRMI.addConnection(connection);
            }

            @Override
            public void received (Connection connection, OtherObjectImpl object) {
                // The test is complete when the client sends the OtherObject instance.
                if (object == serverTestObject.getOtherObject()) {
                    stopEndPoints();
                }
            }
        });


        // ----
        Client client = new Client(connectionOptions);
        register(client.getSerialization());

        addEndPoint(client);
        client.listeners().add(new ListenerRaw<Connection, Object>() {
            @Override
            public void connected(final Connection connection) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        TestObject test = RmiBridge.getRemoteObject(connection, 42, TestObject.class);
                        // Normal remote method call.
                        assertEquals(43.21f, test.other(), .0001f);

                        // Make a remote method call that returns another remote proxy object.
                        OtherObject otherObject = test.getOtherObject();
                        // Normal remote method call on the second object.
                        float value = otherObject.value();
                        assertEquals(12.34f, value, .0001f);

                        // When a remote proxy object is sent, the other side receives its actual remote object.
                        // we have to manually flush, since we are in a separate thread that does not auto-flush.
                        connection.send().TCP(otherObject).flush();
                    }
                }).start();
            }
        });

        client.connect(5000);

        waitForThreads(20);
    }

    /** Registers the same classes in the same order on both the client and server. */
    static public void register(SerializationManager kryoMT) {
        kryoMT.register(TestObject.class);
        kryoMT.register(OtherObject.class, new RemoteObjectSerializer<OtherObject>());
        RmiBridge.registerClasses(kryoMT);
    }

    static public interface TestObject {
        public float other();

        public OtherObject getOtherObject();
    }

    static public class TestObjectImpl implements TestObject {
        public OtherObject otherObject;

        @Override
        public float other() {
            return 43.21f;
        }

        @Override
        public OtherObject getOtherObject() {
            return otherObject;
        }
    }

    static public interface OtherObject {
        public float value ();
    }

    static public class OtherObjectImpl implements OtherObject {
        @Override
        public float value () {
            return 12.34f;
        }
    }
}
