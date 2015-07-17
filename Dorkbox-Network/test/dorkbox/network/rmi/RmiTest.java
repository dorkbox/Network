package dorkbox.network.rmi;


import dorkbox.network.BaseTest;
import dorkbox.network.Client;
import dorkbox.network.ConnectionOptions;
import dorkbox.network.Server;
import dorkbox.network.connection.Connection;
import dorkbox.network.connection.Listener;
import dorkbox.network.util.ConnectionSerializationManager;
import dorkbox.network.util.KryoConnectionSerializationManager;
import dorkbox.network.util.exceptions.InitializationException;
import dorkbox.network.util.exceptions.SecurityException;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public
class RmiTest extends BaseTest {

    private static final int CLIENT_ID = 4321;
    private static final int SERVER_ID = 1234;

    private static final int CLIENT_REMOTE_ID = 42;
    private static final int SERVER_REMOTE_ID = 12;

    private static
    void runTest(final Connection connection, final int id, final int otherID) {
        new Thread() {
            @Override
            public
            void run() {
                System.err.println("Starting test for: " + id);

                TestObject test = connection.getRemoteObject(id, TestObject.class);
                RemoteObject remoteObject = (RemoteObject) test;

                // Default behavior. RMI is transparent, method calls behave like normal
                // (return values and exceptions are returned, call is synchronous)
                System.err.println("hashCode: " + test.hashCode());
                System.err.println("toString: " + test);
                test.moo();
                test.moo("Cow");
                assertEquals(otherID, test.id());


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
                assertEquals(otherID, remoteObject.waitForLastResponse());

                assertEquals(0, test.id());
                byte responseID = remoteObject.getLastResponseID();
                assertEquals(otherID, remoteObject.waitForResponse(responseID));

                // Non-blocking call that errors out
                remoteObject.setTransmitReturnValue(false);
                test.throwException();
                assertEquals(remoteObject.waitForLastResponse()
                                         .getClass(), UnsupportedOperationException.class);

                // Call will time out if non-blocking isn't working properly
                remoteObject.setTransmitExceptions(false);
                test.moo("Mooooooooo", 3000);

                // Test sending a reference to a remote object.
                MessageWithTestObject m = new MessageWithTestObject();
                m.number = 678;
                m.text = "sometext";
                m.testObject = connection.getRemoteObject(id, TestObject.class);
                connection.send()
                          .TCP(m)
                          .flush();
            }
        }.start();
    }

    public static
    void register(ConnectionSerializationManager kryoMT) {
        kryoMT.register(Object.class); // Needed for Object#toString, hashCode, etc.
        kryoMT.register(TestObject.class);
        kryoMT.register(MessageWithTestObject.class);
        kryoMT.register(StackTraceElement.class);
        kryoMT.register(StackTraceElement[].class);

        kryoMT.register(UnsupportedOperationException.class);
    }

    @Test
    public
    void rmi() throws InitializationException, SecurityException, IOException {
        ConnectionOptions connectionOptions = new ConnectionOptions();
        connectionOptions.tcpPort = tcpPort;
        connectionOptions.udpPort = udpPort;
        connectionOptions.host = host;
        connectionOptions.enableRmi = true;
        connectionOptions.serializationManager = KryoConnectionSerializationManager.DEFAULT(true, true);
        register(connectionOptions.serializationManager);

        final Server server = new Server(connectionOptions);
        server.disableRemoteKeyValidation();
        addEndPoint(server);
        server.bind(false);

        // have to have this happen BEFORE any connections are made.
        server.rmi()
              .register(SERVER_REMOTE_ID, new TestObjectImpl(SERVER_ID));

        server.listeners()
              .add(new Listener<MessageWithTestObject>() {
                  @Override
                  public
                  void received(Connection connection, MessageWithTestObject m) {
                      assertEquals(SERVER_ID, m.testObject.id());
                      System.err.println("Client Finished!");

                      // normally this is in the 'connected', but we do it here, so that it's more linear and easier to debug
                      runTest(connection, CLIENT_REMOTE_ID, CLIENT_ID);
                  }
              });


        // ----

        final Client client = new Client(connectionOptions);
        client.disableRemoteKeyValidation();

        addEndPoint(client);

        // have to have this happen BEFORE any connections are made.
        client.rmi()
              .register(CLIENT_REMOTE_ID, new TestObjectImpl(CLIENT_ID));

        client.listeners()
              .add(new Listener<MessageWithTestObject>() {
                  @Override
                  public
                  void connected(final Connection connection) {
                      RmiTest.runTest(connection, SERVER_REMOTE_ID, SERVER_ID);
                  }

                  @Override
                  public
                  void received(Connection connection, MessageWithTestObject m) {
                      assertEquals(CLIENT_ID, m.testObject.id());
                      System.err.println("Server Finished!");

                      stopEndPoints(2000);
                  }
              });

        client.connect(5000);
        waitForThreads(30);
    }

    @Test
    public
    void rmiMany() throws InitializationException, SecurityException, IOException {
        ConnectionOptions connectionOptions = new ConnectionOptions();
        connectionOptions.tcpPort = tcpPort;
        connectionOptions.host = host;
        connectionOptions.enableRmi = true;
        connectionOptions.serializationManager = KryoConnectionSerializationManager.DEFAULT(true, true);
        register(connectionOptions.serializationManager);


        final Server server = new Server(connectionOptions);
        server.disableRemoteKeyValidation();
        addEndPoint(server);
        server.bind(false);

        // have to have this happen BEFORE any connections are made.
        final TestObjectImpl serverTestObject = new TestObjectImpl(CLIENT_ID);
        server.rmi()
              .register(CLIENT_REMOTE_ID, serverTestObject);

        server.listeners()
              .add(new Listener<MessageWithTestObject>() {
                  @Override
                  public
                  void received(Connection connection, MessageWithTestObject m) {
                      assertEquals(256 + 512 + 1024, serverTestObject.moos);
                      System.err.println("Client Finished!");
                      stopEndPoints(2000);
                  }
              });


        // ----

        final Client client = new Client(connectionOptions);
        client.disableRemoteKeyValidation();

        addEndPoint(client);

        client.listeners()
              .add(new Listener<MessageWithTestObject>() {
                  @Override
                  public
                  void connected(final Connection connection) {
                      new Thread() {
                          @Override
                          public
                          void run() {
                              TestObject test = connection.getRemoteObject(CLIENT_REMOTE_ID, TestObject.class);

                              for (int i = 0; i < 256; i++) {
                                  assertEquals(CLIENT_ID, test.id());
                              }

                              for (int i = 0; i < 256; i++) {
                                  test.moo();
                              }
                              for (int i = 0; i < 256; i++) {
                                  test.moo("" + i);
                              }
                              for (int i = 0; i < 256; i++) {
                                  test.moo("" + i, 0);
                              }

                              connection.send()
                                        .TCP(new MessageWithTestObject())
                                        .flush();
                          }
                      }.start();
                  }
              });

        client.connect(5000);
        waitForThreads(30);
    }

    @Test
    public
    void rmiSlow() throws InitializationException, SecurityException, IOException {
        ConnectionOptions connectionOptions = new ConnectionOptions();
        connectionOptions.tcpPort = tcpPort;
        connectionOptions.host = host;
        connectionOptions.enableRmi = true;
        connectionOptions.serializationManager = KryoConnectionSerializationManager.DEFAULT(true, true);
        register(connectionOptions.serializationManager);


        final Server server = new Server(connectionOptions);
        server.disableRemoteKeyValidation();
        addEndPoint(server);
        server.bind(false);

        // have to have this happen BEFORE any connections are made.
        final TestObjectImpl serverTestObject = new TestObjectImpl(CLIENT_ID);
        server.rmi()
              .register(CLIENT_REMOTE_ID, serverTestObject);

        server.listeners()
              .add(new Listener<MessageWithTestObject>() {
                  @Override
                  public
                  void received(Connection connection, MessageWithTestObject m) {
                      System.err.println("Client Finished!");
                      stopEndPoints(2000);
                  }
              });



        // ----

        final Client client = new Client(connectionOptions);
        client.disableRemoteKeyValidation();

        addEndPoint(client);

        client.listeners()
              .add(new Listener<MessageWithTestObject>() {
                  @Override
                  public
                  void connected(final Connection connection) {
                      new Thread() {
                          @Override
                          public
                          void run() {
                              TestObject test = connection.getRemoteObject(CLIENT_REMOTE_ID, TestObject.class);
                              test.id();
                              // Timeout on purpose.
                              try {
                                  ((RemoteObject) test).setResponseTimeout(200);
                                  test.slow();
                                  Assert.fail();
                              } catch (TimeoutException ignored) {
                              }
                              try {
                                  Thread.sleep(300);
                              } catch (InterruptedException ignored) {
                              }

                              ((RemoteObject) test).setResponseTimeout(3000);
                              connection.send()
                                        .TCP(new MessageWithTestObject())
                                        .flush();
                          }
                      }.start();
                  }
              });


        client.connect(5000);
        waitForThreads(30);
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
        private final int id;
        public long value = System.currentTimeMillis();
        public int moos;

        public
        TestObjectImpl(int id) {
            this.id = id;
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
            return this.id;
        }

        @Override
        public
        float slow() {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
            }
            return 666;
        }
    }


    public static
    class MessageWithTestObject implements RmiMessages {
        public int number;
        public String text;
        public TestObject testObject;
    }
}
