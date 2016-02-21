package dorkbox.network.rmi;

import dorkbox.network.BaseTest;
import dorkbox.network.Client;
import dorkbox.network.Configuration;
import dorkbox.network.Server;
import dorkbox.network.connection.Connection;
import dorkbox.network.connection.KryoCryptoSerializationManager;
import dorkbox.network.connection.Listener;
import dorkbox.util.exceptions.InitializationException;
import dorkbox.util.exceptions.SecurityException;
import dorkbox.util.serialization.IgnoreSerialization;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

@SuppressWarnings("Duplicates")
public
class RmiSendObjectTest extends BaseTest {

    /**
     * In this test the server has two objects in an object space. The client
     * uses the first remote object to get the second remote object.
     */
    @Test
    public
    void rmi() throws InitializationException, SecurityException, IOException, InterruptedException {
        KryoCryptoSerializationManager.DEFAULT = KryoCryptoSerializationManager.DEFAULT();
        KryoCryptoSerializationManager.DEFAULT.registerRemote(TestObject.class, TestObjectImpl.class);
        KryoCryptoSerializationManager.DEFAULT.registerRemote(OtherObject.class, OtherObjectImpl.class);


        Configuration configuration = new Configuration();
        configuration.tcpPort = tcpPort;
        configuration.host = host;
        configuration.rmiEnabled = true;

        Server server = new Server(configuration);
        server.disableRemoteKeyValidation();
        server.setIdleTimeout(0);


        addEndPoint(server);
        server.bind(false);


        server.listeners()
              .add(new Listener<OtherObjectImpl>() {
                  @Override
                  public
                  void received(Connection connection, OtherObjectImpl object) {
                      // The test is complete when the client sends the OtherObject instance.
                      if (object.value() == 12.34F) {
                          stopEndPoints();
                      } else {
                          fail("Incorrect object value");
                      }
                  }
              });


        // ----
        Client client = new Client(configuration);
        client.disableRemoteKeyValidation();
        client.setIdleTimeout(0);

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


                              TestObject test = null;
                              try {
                                  test = connection.createProxyObject(TestObjectImpl.class);

                                  test.setOther(43.21f);
                                  // Normal remote method call.
                                  assertEquals(43.21f, test.other(), 0.0001F);

                                  // Make a remote method call that returns another remote proxy object.
                                  OtherObject otherObject = test.getOtherObject();
                                  // Normal remote method call on the second object.
                                  otherObject.setValue(12.34f);
                                  float value = otherObject.value();
                                  assertEquals(12.34f, value, 0.0001F);

                                  // When a remote proxy object is sent, the other side receives its actual remote object.
                                  // we have to manually flush, since we are in a separate thread that does not auto-flush.
                                  connection.send()
                                            .TCP(otherObject)
                                            .flush();
                              } catch (IOException e) {
                                  e.printStackTrace();
                                  fail();
                              }
                          }
                      }).start();
                  }
              });

        client.connect(5000);

        waitForThreads(20);
    }

    public
    interface TestObject {
        void setOther(float aFloat);

        float other();

        OtherObject getOtherObject();
    }


    public
    interface OtherObject {
        void setValue(float aFloat);
        float value();
    }


    private static final AtomicInteger idCounter = new AtomicInteger();


    public static
    class TestObjectImpl implements TestObject {
        @IgnoreSerialization
        private final int ID = idCounter.getAndIncrement();

        @RMI
        private final OtherObject otherObject = new OtherObjectImpl();
        private float aFloat;


        @Override
        public
        void setOther(final float aFloat) {
            this.aFloat = aFloat;
        }

        @Override
        public
        float other() {
            return aFloat;
        }

        @Override
        public
        OtherObject getOtherObject() {
            return this.otherObject;
        }

        @Override
        public
        int hashCode() {
            return ID;
        }
    }


    public static
    class OtherObjectImpl implements OtherObject {
        @IgnoreSerialization
        private final int ID = idCounter.getAndIncrement();

        private float aFloat;

        @Override
        public
        void setValue(final float aFloat) {
            this.aFloat = aFloat;
        }

        @Override
        public
        float value() {
            return aFloat;
        }

        @Override
        public
        int hashCode() {
            return ID;
        }
    }
}
