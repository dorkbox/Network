
package dorkbox.network;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.ConnectionImpl;
import dorkbox.network.connection.Listener;
import dorkbox.network.connection.ListenerRaw;
import dorkbox.network.util.exceptions.InitializationException;
import dorkbox.network.util.exceptions.SecurityException;

public class ListenerTest extends BaseTest {

    private final String origString = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"; // lots of a's to encourage compression
    private final int limit = 20;
    private AtomicInteger count = new AtomicInteger(0);

    volatile String fail = null;
    AtomicBoolean subClassWorkedOK = new AtomicBoolean(false);
    AtomicBoolean subClassWorkedOK2 = new AtomicBoolean(false);
    AtomicBoolean superClassWorkedOK = new AtomicBoolean(false);
    AtomicBoolean superClass2WorkedOK = new AtomicBoolean(false);
    AtomicBoolean disconnectWorkedOK = new AtomicBoolean(false);

    // quick and dirty test to also test connection sub-classing
    class TestConnectionA extends ConnectionImpl {
        public TestConnectionA(String name) {
            super(name);
        }

        public void check() {
            ListenerTest.this.subClassWorkedOK.set(true);
        }
    }

    class TestConnectionB extends TestConnectionA {
        public TestConnectionB(String name) {
            super(name);
        }

        @Override
        public void check() {
            ListenerTest.this.subClassWorkedOK.set(true);
        }
    }



    @SuppressWarnings("rawtypes")
    @Test
    public void listener() throws IOException, SecurityException, InitializationException {
        ConnectionOptions connectionOptions = new ConnectionOptions();
        connectionOptions.tcpPort = tcpPort;
        connectionOptions.host = host;

        Server server = new Server(connectionOptions) {
            @Override
            public TestConnectionA newConnection(String name) {
                return new TestConnectionA(name);
            }
        };

        server.disableRemoteKeyValidation();
        addEndPoint(server);
        server.bind(false);

        server.listeners().add(new ListenerRaw<TestConnectionA, String>() {
            @Override
            public void received (TestConnectionA connection, String string) {
                connection.check();
//                System.err.println("default check");
                connection.send().TCP(string);
            }
        });

        server.listeners().add(new Listener<String>() {
            @Override
            public void received (Connection connection, String string) {
//                System.err.println("subclass check");
                ListenerTest.this.subClassWorkedOK2.set(true);
            }
        });

        // should be able to happen!
        server.listeners().add(new Listener() {
            @Override
            public void received(Connection connection, Object string) {
//                System.err.println("generic class check");
                ListenerTest.this.superClassWorkedOK.set(true);
            }
        });


        // should be able to happen!
        server.listeners().add(new ListenerRaw() {
            @Override
            public void received(Connection connection, Object string) {
//                System.err.println("generic class check");
                ListenerTest.this.superClass2WorkedOK.set(true);
            }
        });

        server.listeners().add(new Listener() {
            @Override
            public void disconnected(Connection connection) {
//                System.err.println("disconnect check");
                ListenerTest.this.disconnectWorkedOK.set(true);
            }
        });

        // should not let this happen!
        try {
            server.listeners().add(new ListenerRaw<TestConnectionB, String>() {
                @Override
                public void received (TestConnectionB connection, String string) {
                    connection.check();
                    System.err.println(string);
                    connection.send().TCP(string);
                }
            });
            this.fail = "Should not be able to ADD listeners that are NOT the basetype or the interface";
        } catch (Exception e) {
            System.err.println("Successfully did NOT add listener that was not the base class");
        }


        // ----

        Client client = new Client(connectionOptions);

        client.disableRemoteKeyValidation();
        addEndPoint(client);
        client.listeners().add(new Listener<String>() {
            @Override
            public void connected (Connection connection) {
                connection.send().TCP(ListenerTest.this.origString); // 20 a's
            }

            @Override
            public void received (Connection connection, String string) {
                if (ListenerTest.this.count.get() < ListenerTest.this.limit) {
                    ListenerTest.this.count.getAndIncrement();
                    connection.send().TCP(string);
                } else {
                    if (!ListenerTest.this.origString.equals(string)) {
                        ListenerTest.this.fail = "original string not equal to the string received";
                    }
                    stopEndPoints();
                }
            }
        });


        client.connect(5000);

        waitForThreads();
        assertEquals(this.limit, this.count.get());
        assertTrue(this.subClassWorkedOK.get());
        assertTrue(this.subClassWorkedOK2.get());
        assertTrue(this.superClassWorkedOK.get());
        assertTrue(this.superClass2WorkedOK.get());
        assertTrue(this.disconnectWorkedOK.get());

        if (this.fail != null) {
            fail(this.fail);
        }
    }
}
