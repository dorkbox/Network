package dorkbox.network.rmi;

import dorkbox.network.BaseTest;
import dorkbox.network.Client;
import dorkbox.network.ConnectionOptions;
import dorkbox.network.Server;
import dorkbox.network.connection.Connection;
import dorkbox.network.connection.EndPoint;
import dorkbox.network.connection.Listener;
import dorkbox.network.util.ConnectionSerializationManager;
import dorkbox.network.util.exceptions.InitializationException;
import dorkbox.network.util.exceptions.SecurityException;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;

public
class RmiSendObjectTest extends BaseTest {
    private Rmi serverRMI;

    /**
     * In this test the server has two objects in an object space. The client
     * uses the first remote object to get the second remote object.
     */
    @Test
    public
    void rmi() throws InitializationException, SecurityException, IOException {
        ConnectionOptions connectionOptions = new ConnectionOptions();
        connectionOptions.tcpPort = tcpPort;
        connectionOptions.host = host;
        connectionOptions.enableRmi = true;

        Server server = new Server(connectionOptions);
        server.disableRemoteKeyValidation();
        ConnectionSerializationManager serverSerializationManager = server.getSerialization();
        register(server, serverSerializationManager);
        addEndPoint(server);
        server.bind(false);


        // After all common registrations, register OtherObjectImpl only on the server using the remote object interface ID.
        // This causes OtherObjectImpl to be serialized as OtherObject.
        int otherObjectID = serverSerializationManager.getRegistration(OtherObject.class)
                                                      .getId();
        serverSerializationManager.register(OtherObjectImpl.class, new RemoteObjectSerializer<OtherObjectImpl>(server), otherObjectID);


        // TestObjectImpl has a reference to an OtherObjectImpl.
        final TestObjectImpl serverTestObject = new TestObjectImpl();
        serverTestObject.otherObject = new OtherObjectImpl();

        // Both objects must be registered with the ObjectSpace.
        this.serverRMI = server.rmi();
        this.serverRMI.register(42, serverTestObject);
        this.serverRMI.register(777, serverTestObject.getOtherObject());

        server.listeners()
              .add(new Listener<OtherObjectImpl>() {
                  @Override
                  public
                  void received(Connection connection, OtherObjectImpl object) {
                      // The test is complete when the client sends the OtherObject instance.
                      if (object == serverTestObject.getOtherObject()) {
                          stopEndPoints();
                      }
                  }
              });


        // ----
        Client client = new Client(connectionOptions);
        client.disableRemoteKeyValidation();
        register(client, client.getSerialization());

        addEndPoint(client);
        client.listeners()
              .add(new Listener<Object>() {
                  @Override
                  public
                  void connected(final Connection connection) {
                      new Thread(new Runnable() {
                          @Override
                          public
                          void run() {
                              TestObject test = connection.getRemoteObject(42, TestObject.class);
                              // Normal remote method call.
                              assertEquals(43.21f, test.other(), .0001f);

                              // Make a remote method call that returns another remote proxy object.
                              OtherObject otherObject = test.getOtherObject();
                              // Normal remote method call on the second object.
                              float value = otherObject.value();
                              assertEquals(12.34f, value, .0001f);

                              // When a remote proxy object is sent, the other side receives its actual remote object.
                              // we have to manually flush, since we are in a separate thread that does not auto-flush.
                              connection.send()
                                        .TCP(otherObject)
                                        .flush();
                          }
                      }).start();
                  }
              });

        client.connect(5000);

        waitForThreads(20);
    }

    /**
     * Registers the same classes in the same order on both the client and server.
     */
    public static
    void register(EndPoint endpoint, ConnectionSerializationManager kryoMT) {
        kryoMT.register(TestObject.class);
        kryoMT.register(OtherObject.class, new RemoteObjectSerializer<OtherObject>(endpoint));
    }

    public
    interface TestObject {
        float other();

        OtherObject getOtherObject();
    }


    public static
    class TestObjectImpl implements TestObject {
        public OtherObject otherObject;

        @Override
        public
        float other() {
            return 43.21f;
        }

        @Override
        public
        OtherObject getOtherObject() {
            return this.otherObject;
        }
    }


    public
    interface OtherObject {
        float value();
    }


    public static
    class OtherObjectImpl implements OtherObject {
        @Override
        public
        float value() {
            return 12.34f;
        }
    }
}
