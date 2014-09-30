
package dorkbox.network.rmi;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.junit.Test;

import dorkbox.network.BaseTest;
import dorkbox.network.Client;
import dorkbox.network.ConnectionOptions;
import dorkbox.network.Server;
import dorkbox.network.connection.Connection;
import dorkbox.network.connection.Listener;
import dorkbox.network.util.SerializationManager;
import dorkbox.network.util.exceptions.InitializationException;
import dorkbox.network.util.exceptions.SecurityException;

public class RmiTest extends BaseTest {

    private static final int CLIENT_ID = 4321;
    private static final int SERVER_ID = 1234;

    private static final int REMOTE_ID_ON_CLIENT = 42;
    private static final int REMOTE_ID_ON_SERVER = 12;

    @Test
    public void rmi() throws IOException, InitializationException, SecurityException {
        ConnectionOptions connectionOptions = new ConnectionOptions();
        connectionOptions.tcpPort = tcpPort;
        connectionOptions.host = host;

        Server server = new Server(connectionOptions);
        server.disableRemoteKeyValidation();
        register(server.getSerialization());
        addEndPoint(server);
        server.bind(false);

        server.listeners().add(new Listener<MessageWithTestObject>() {
            @Override
            public void received (Connection connection, MessageWithTestObject m) {
                assertEquals(SERVER_ID, m.testObject.id());
                System.err.println("Client Finished!");

                runTest(connection, REMOTE_ID_ON_CLIENT, CLIENT_ID);
            }
        });

        // ----

        Client client = new Client(connectionOptions);
        client.disableRemoteKeyValidation();
        register(client.getSerialization());

        addEndPoint(client);
        client.listeners().add(new Listener<MessageWithTestObject>() {
            @Override
            public void connected (final Connection connection) {
                RmiTest.runTest(connection, REMOTE_ID_ON_SERVER, SERVER_ID);
            }

            @Override
            public void received (Connection connection, MessageWithTestObject m) {
                assertEquals(CLIENT_ID, m.testObject.id());
                System.err.println("Server Finished!");

                stopEndPoints(2000);
            }
        });


        // have to have this happen BEFORE any connections are made.
        RmiBridge clientRMI = client.getRmiBridge();
        clientRMI.register(REMOTE_ID_ON_CLIENT, new TestObjectImpl(CLIENT_ID));


        RmiBridge serverRMI = server.getRmiBridge();
        serverRMI.register(REMOTE_ID_ON_SERVER, new TestObjectImpl(SERVER_ID));

        client.connect(5000);

        waitForThreads(30);
    }

    private static void runTest(final Connection connection, final int id, final int otherID) {
        new Thread() {
            @Override
            public void run () {
                System.err.println("Starting test for: " + id);

                TestObject test = RmiBridge.getRemoteObject(connection, id, TestObject.class);
                RemoteObject remoteObject = (RemoteObject)test;

                // Default behavior. RMI is transparent, method calls behave like normal
                // (return values and exceptions are returned, call is synchronous)
                test.moo();
                test.moo("Cow");
                assertEquals(otherID, test.id());

                // Test that RMI correctly waits for the remotely invoked method to exit
                remoteObject.setResponseTimeout(5000);
                test.moo("You should see this two seconds before...", 2000);
                System.out.println("...This");
                remoteObject.setResponseTimeout(3000);

                // Try exception handling
                boolean caught = false;
                try {
                    test.throwException();
                } catch(UnsupportedOperationException ex) {
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
                } catch(UnsupportedOperationException ex) {
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
                assertEquals(remoteObject.waitForLastResponse().getClass(), UnsupportedOperationException.class);

                // Call will time out if non-blocking isn't working properly
                remoteObject.setTransmitExceptions(false);
                test.moo("Mooooooooo", 3000);

                // Test sending a reference to a remote object.
                MessageWithTestObject m = new MessageWithTestObject();
                m.number = 678;
                m.text = "sometext";
                m.testObject = RmiBridge.getRemoteObject(connection, id, TestObject.class);
                connection.send().TCP(m).flush();
            }
        }.start();
    }

    static public void register (SerializationManager kryoMT) {
        kryoMT.register(TestObject.class);
        kryoMT.register(MessageWithTestObject.class);
        kryoMT.register(StackTraceElement.class);
        kryoMT.register(StackTraceElement[].class);

        kryoMT.register(UnsupportedOperationException.class);
        kryoMT.setReferences(true); // Needed for UnsupportedOperationException, which has a circular reference in the cause field.

        RmiBridge.registerClasses(kryoMT);
    }

    static public interface TestObject {
        public void throwException();

        public void moo ();

        public void moo (String value);

        public void moo (String value, long delay);

        public int id ();
    }

    static public class TestObjectImpl implements TestObject {
        public long value = System.currentTimeMillis();
        private final int id;

        public TestObjectImpl (int id) {
            this.id = id;
        }

        @Override
        public void throwException() {
            throw new UnsupportedOperationException("Why would I do that?");
        }

        @Override
        public void moo () {
            System.out.println("Moo!");
        }

        @Override
        public void moo (String value) {
            System.out.println("Moo: " + value);
        }

        @Override
        public void moo (String value, long delay) {
            System.out.println("Moo: " + value + " (" + delay + ")");
            try {
                Thread.sleep(delay);
            }
            catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        @Override
        public int id () {
            return this.id;
        }
    }

    static public class MessageWithTestObject implements RmiMessages {
        public int number;
        public String text;
        public TestObject testObject;
    }
}
