
package dorkbox.network;


import static org.junit.Assert.fail;
import hive.common.Listener;

import java.io.IOException;

import org.junit.Test;

import dorkbox.network.connection.Connection;
import dorkbox.network.util.exceptions.InitializationException;
import dorkbox.network.util.exceptions.SecurityException;

public class DiscoverHostTest extends BaseTest {
    volatile boolean connected = false;

    @Test
    public void broadcast () throws IOException, InitializationException, SecurityException {

        ConnectionOptions connectionOptions = new ConnectionOptions();
        connectionOptions.tcpPort = tcpPort;
        connectionOptions.udpPort = udpPort;
        connectionOptions.host = host;

        Server server = new Server(connectionOptions);
        addEndPoint(server);
        server.bind(false);

        // ----

        String host = Broadcast.discoverHost(udpPort, 2000);
        if (host == null) {
            stopEndPoints();
            fail("No servers found. Maybe you are behind a VPN service or your network is mis-configured?");
            return;
        }

        Client client = new Client(connectionOptions);
        addEndPoint(client);
        client.listeners().add(new Listener<Object>() {
            @Override
            public void connected(Connection connection) {
                DiscoverHostTest.this.connected = true;
                stopEndPoints();
            }
        });
        client.connect(2000);

        waitForThreads(2);

        if (!this.connected) {
            fail("Unable to connect to server.");
        }
    }
}
