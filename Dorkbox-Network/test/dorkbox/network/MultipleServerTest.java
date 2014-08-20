
package dorkbox.network;

import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.Listener;
import dorkbox.network.util.InitializationException;
import dorkbox.network.util.SecurityException;

public class MultipleServerTest extends DorknetTestCase {
    AtomicInteger received = new AtomicInteger();

    @Test
    public void multipleServers() throws IOException, InitializationException, SecurityException {

        ConnectionOptions connectionOptions1 = new ConnectionOptions();
        connectionOptions1.tcpPort = tcpPort;
        connectionOptions1.udpPort = udpPort;
        connectionOptions1.localChannelName = "chan1";


        Server server1 = new Server(connectionOptions1);
        server1.getSerialization().register(String[].class);
        addEndPoint(server1);

        server1.bind(false);
        server1.listeners().add(new Listener<Connection, String>() {
            @Override
            public void received (Connection connection, String object) {
                if (!object.equals("client1")) {
                    fail();
                }
                if (received.incrementAndGet() == 2) {
                    stopEndPoints();
                }
            }
        });

        ConnectionOptions connectionOptions2 = new ConnectionOptions();
        connectionOptions2.tcpPort = tcpPort+1;
        connectionOptions2.udpPort = udpPort+1;
        connectionOptions2.localChannelName = "chan2";


        Server server2 = new Server(connectionOptions2);
        server2.getSerialization().register(String[].class);
        addEndPoint(server2);
        server2.bind(false);
        server2.listeners().add(new Listener<Connection, String>() {
            @Override
            public void received (Connection connection, String object) {
                if (!object.equals("client2")) {
                    fail();
                }
                if (received.incrementAndGet() == 2) {
                    stopEndPoints();
                }
            }
        });

        // ----

        connectionOptions1.localChannelName = null;
        connectionOptions1.host = host;
        Client client1 = new Client(connectionOptions1);
        client1.getSerialization().register(String[].class);
        addEndPoint(client1);
        client1.listeners().add(new Listener<Connection, String>() {
            @Override
            public void connected (Connection connection) {
                connection.send().TCP("client1");
            }
        });
        client1.connect(5000);


        connectionOptions2.localChannelName = null;
        connectionOptions2.host = host;
        Client client2 = new Client(connectionOptions2);
        client2.getSerialization().register(String[].class);
        addEndPoint(client2);
        client2.listeners().add(new Listener<Connection, String>() {
            @Override
            public void connected (Connection connection) {
                connection.send().TCP("client2");
            }
        });
        client2.connect(5000);

        waitForThreads(30);
    }
}
