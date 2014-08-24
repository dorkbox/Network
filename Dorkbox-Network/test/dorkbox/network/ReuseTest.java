
package dorkbox.network;


import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.Listener;
import dorkbox.network.util.exceptions.InitializationException;
import dorkbox.network.util.exceptions.SecurityException;

public class ReuseTest extends BaseTest {
    AtomicInteger serverCount;
    AtomicInteger clientCount;

    @Test
    public void socketReuse() throws IOException, InitializationException, SecurityException {
        serverCount = new AtomicInteger(0);
        clientCount = new AtomicInteger(0);

        ConnectionOptions connectionOptions = new ConnectionOptions();
        connectionOptions.tcpPort = tcpPort;
        connectionOptions.udpPort = udpPort;
        connectionOptions.host = host;

        Server server = new Server(connectionOptions);
        addEndPoint(server);
        server.listeners().add(new Listener<Connection, String>() {
            @Override
            public void connected (Connection connection) {
                connection.send().TCP("-- TCP from server");
                connection.send().UDP("-- UDP from server");
            }

            @Override
            public void received (Connection connection, String object) {
                int incrementAndGet = serverCount.incrementAndGet();
                System.err.println("<S " + connection + "> " + incrementAndGet + " : " + object);
            }
        });

        // ----

        Client client = new Client(connectionOptions);
        addEndPoint(client);
        client.listeners().add(new Listener<Connection, String>() {
            @Override
            public void connected (Connection connection) {
                connection.send().TCP("-- TCP from client");
                connection.send().UDP("-- UDP from client");
            }

            @Override
            public void received (Connection connection, String object) {
                int incrementAndGet = clientCount.incrementAndGet();
                System.err.println("<C " + connection + "> " + incrementAndGet + " : " + object);
            }
        });

        server.bind(false);
        int count = 10;
        for (int i = 1; i < count+1; i++) {
            client.connect(5000);

            int target = i*2;
            while (serverCount.get() != target || clientCount.get() != target) {
                System.err.println("Waiting...");
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ex) {
                }
            }

            client.close();
        }

        assertEquals(count * 2 * 2, clientCount.get() + serverCount.get());

        stopEndPoints();
        waitForThreads(10);
    }

    @Test
    public void localReuse() throws IOException, InitializationException, SecurityException {
        serverCount = new AtomicInteger(0);
        clientCount = new AtomicInteger(0);

        Server server = new Server();
        addEndPoint(server);
        server.listeners().add(new Listener<Connection, String>() {
            @Override
            public void connected (Connection connection) {
                connection.send().TCP("-- LOCAL from server");
            }

            @Override
            public void received (Connection connection, String object) {
                int incrementAndGet = serverCount.incrementAndGet();
                System.err.println("<S " + connection + "> " + incrementAndGet + " : " + object);
            }
        });

        // ----

        Client client = new Client();
        addEndPoint(client);
        client.listeners().add(new Listener<Connection, String>() {
            @Override
            public void connected (Connection connection) {
                connection.send().TCP("-- LOCAL from client");
            }

            @Override
            public void received (Connection connection, String object) {
                int incrementAndGet = clientCount.incrementAndGet();
                System.err.println("<C " + connection + "> " + incrementAndGet + " : " + object);
            }
        });

        server.bind(false);
        int count = 10;
        for (int i = 1; i < count+1; i++) {
            client.connect(5000);

            int target = i;
            while (serverCount.get() != target || clientCount.get() != target) {
                System.err.println("Waiting...");
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ex) {
                }
            }

            client.close();
        }

        assertEquals(count * 2, clientCount.get() + serverCount.get());

        stopEndPoints();
        waitForThreads(10);
    }
}
