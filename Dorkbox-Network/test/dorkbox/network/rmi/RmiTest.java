package dorkbox.network.rmi;


import dorkbox.network.BaseTest;
import dorkbox.network.Client;
import dorkbox.network.Configuration;
import dorkbox.network.Server;
import dorkbox.network.connection.Connection;
import dorkbox.network.connection.KryoCryptoSerializationManager;
import dorkbox.network.connection.Listener;
import dorkbox.network.util.CryptoSerializationManager;
import dorkbox.util.exceptions.InitializationException;
import dorkbox.util.exceptions.SecurityException;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public
class RmiTest extends BaseTest {

    private static
    void runTest(final Connection connection, final int remoteObjectID) {
        new Thread() {
            @Override
            public
            void run() {
                try {
                    TestObject test = connection.createProxyObject(TestObjectImpl.class);

                    System.err.println("Starting test for: " + remoteObjectID);

                    //TestObject test = connection.getRemoteObject(id, TestObject.class);
                    RemoteObject remoteObject = (RemoteObject) test;

                    final String s = remoteObject.toString();
                    remoteObject.enableToString(true);
                    assertFalse(s.equals(remoteObject.toString()));



                    // Default behavior. RMI is transparent, method calls behave like normal
                    // (return values and exceptions are returned, call is synchronous)
                    System.err.println("hashCode: " + test.hashCode());
                    System.err.println("toString: " + test);
                    test.moo();
                    test.moo("Cow");
                    assertEquals(remoteObjectID, test.id());


                    // UDP calls that ignore the return value
                    remoteObject.setUDP(true);
                    test.moo("Meow");
                    assertEquals(0, test.id());
                    remoteObject.setUDP(false);


                    // Test that RMI correctly waits for the remotely invoked method to exit
                    remoteObject.setResponseTimeout(5000);
                    test.moo("You should see this two seconds before...", 2000);
                    System.out.println("...This");
                    remoteObject.setResponseTimeout(3000);

                    // Try exception handling
                    boolean caught = false;
                    try {
                        test.throwException();
                    } catch (UnsupportedOperationException ex) {
                        System.err.println("\tExpected.");
                        caught = true;
                    }
                    assertTrue(caught);

                    // Return values are ignored, but exceptions are still dealt with properly

                    remoteObject.setTransmitReturnValue(false);
                    test.moo("Baa");
                    test.id();
                    caught = false;
                    try {
                        test.throwException();
                    } catch (UnsupportedOperationException ex) {
                        caught = true;
                    }
                    assertTrue(caught);

                    // Non-blocking call that ignores the return value
                    remoteObject.setNonBlocking(true);
                    remoteObject.setTransmitReturnValue(false);
                    test.moo("Meow");
                    assertEquals(0, test.id());

                    // Non-blocking call that returns the return value
                    remoteObject.setTransmitReturnValue(true);
                    test.moo("Foo");

                    assertEquals(0, test.id());
                    // wait for the response to id()
                    assertEquals(remoteObjectID, remoteObject.waitForLastResponse());

                    assertEquals(0, test.id());
                    byte responseID = remoteObject.getLastResponseID();
                    // wait for the response to id()
                    assertEquals(remoteObjectID, remoteObject.waitForResponse(responseID));

                    // Non-blocking call that errors out
                    remoteObject.setTransmitReturnValue(false);
                    test.throwException();
                    assertEquals(remoteObject.waitForLastResponse()
                                             .getClass(), UnsupportedOperationException.class);

                    // Call will time out if non-blocking isn't working properly
                    remoteObject.setTransmitExceptions(false);
                    test.moo("Mooooooooo", 3000);

                    // should wait for a small time
                    remoteObject.setTransmitReturnValue(true);
                    remoteObject.setNonBlocking(false);
                    remoteObject.setResponseTimeout(6000);
                    System.out.println("You should see this 2 seconds before");
                    float slow = test.slow();
                    System.out.println("...This");
                    assertEquals(slow, 123, .0001D);

                    // Test sending a reference to a remote object.
                    MessageWithTestObject m = new MessageWithTestObject();
                    m.number = 678;
                    m.text = "sometext";
                    m.testObject = test;
                    connection.send()
                              .TCP(m)
                              .flush();

                } catch (IOException e) {
                    e.printStackTrace();
                    fail();
                }
            }
        }.start();
    }

    public static
    void register(CryptoSerializationManager kryoMT) {
        kryoMT.register(Object.class); // Needed for Object#toString, hashCode, etc.

        kryoMT.registerRemote(TestObject.class, TestObjectImpl.class);
        kryoMT.register(MessageWithTestObject.class);

        kryoMT.register(UnsupportedOperationException.class);
    }

    @Test
    public
    void rmi() throws InitializationException, SecurityException, IOException, InterruptedException {
        KryoCryptoSerializationManager.DEFAULT = KryoCryptoSerializationManager.DEFAULT();
        register(KryoCryptoSerializationManager.DEFAULT);


        Configuration configuration = new Configuration();
        configuration.tcpPort = tcpPort;
        configuration.udpPort = udpPort;
        configuration.host = host;
        configuration.rmiEnabled = true;

        final Server server = new Server(configuration);
        server.disableRemoteKeyValidation();
        server.setIdleTimeout(0);

        addEndPoint(server);
        server.bind(false);

        server.listeners()
              .add(new Listener<MessageWithTestObject>() {
                  @Override
                  public
                  void connected(final Connection connection) {
                      RmiTest.runTest(connection, 1);
                  }

                  @Override
                  public
                  void received(Connection connection, MessageWithTestObject m) {
                      TestObject object = m.testObject;
                      final int id = object.id();
                      assertEquals(2, id);
                      System.err.println("Client/Server Finished!");

                      stopEndPoints(2000);
                  }

              });


        // ----

        final Client client = new Client(configuration);
        client.setIdleTimeout(0);
        client.disableRemoteKeyValidation();

        addEndPoint(client);

        client.listeners()
              .add(new Listener<MessageWithTestObject>() {
                  @Override
                  public
                  void received(Connection connection, MessageWithTestObject m) {
                      TestObject object = m.testObject;
                      final int id = object.id();
                      assertEquals(1, id);
                      System.err.println("Server/Client Finished!");

                      // normally this is in the 'connected', but we do it here, so that it's more linear and easier to debug
                      runTest(connection, 2);
                  }
              });

        client.connect(5000);

        waitForThreads();
    }

    public
    interface TestObject {
        void throwException();

        void moo();

        void moo(String value);

        void moo(String value, long delay);

        int id();

        float slow();
    }


    public static
    class TestObjectImpl implements TestObject {
        // has to start at 1, because UDP/UDT method invocations ignore return values
        static final AtomicInteger ID_COUNTER = new AtomicInteger(1);

        public long value = System.currentTimeMillis();
        public int moos;
        private final int id = ID_COUNTER.getAndIncrement();

        public
        TestObjectImpl() {
        }

        @Override
        public
        void throwException() {
            throw new UnsupportedOperationException("Why would I do that?");
        }

        @Override
        public
        void moo() {
            this.moos++;
            System.out.println("Moo!");
        }

        @Override
        public
        void moo(String value) {
            this.moos += 2;
            System.out.println("Moo: " + value);
        }

        @Override
        public
        void moo(String value, long delay) {
            this.moos += 4;
            System.out.println("Moo: " + value + " (" + delay + ")");
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        @Override
        public
        int id() {
            return id;
        }

        @Override
        public
        float slow() {
            System.out.println("Slowdown!!");
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return 123f;
        }

        @Override
        public
        boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            final TestObjectImpl that = (TestObjectImpl) o;

            return id == that.id;

        }

        @Override
        public
        int hashCode() {
            return id;
        }

        @Override
        public
        String toString() {
            return "Tada! This is a remote object!";
        }
    }


    public static
    class MessageWithTestObject implements RmiMessages {
        public int number;
        public String text;
        public TestObject testObject;
    }
}
