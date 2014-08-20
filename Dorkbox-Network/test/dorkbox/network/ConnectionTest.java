
package dorkbox.network;


import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;

import org.junit.Test;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.Listener;
import dorkbox.network.util.InitializationException;
import dorkbox.network.util.SecurityException;

public class ConnectionTest extends DorknetTestCase {

    @Test
    public void connectLocal() throws IOException, InitializationException, SecurityException {
        System.out.println("---- " + "Local");
        startServer(null);
        startClient(null);

        waitForThreads(10);
    }

    @Test
    public void connectTcp() throws IOException, InitializationException, SecurityException {
        System.out.println("---- " + "TCP");

        ConnectionOptions connectionOptions = new ConnectionOptions();
        connectionOptions.tcpPort = tcpPort;

        Server server = startServer(connectionOptions);

        connectionOptions = new ConnectionOptions();
        connectionOptions.tcpPort = tcpPort;
        connectionOptions.host = host;
        startClient(connectionOptions);

        server.waitForStop(true);

        waitForThreads(10);
    }

    @Test
    public void connectTcpUdp() throws IOException, InitializationException, SecurityException {
        System.out.println("---- " + "TCP UDP");

        ConnectionOptions connectionOptions = new ConnectionOptions();
        connectionOptions.tcpPort = tcpPort;
        connectionOptions.udpPort = udpPort;

        Server server = startServer(connectionOptions);

        connectionOptions = new ConnectionOptions();
        connectionOptions.tcpPort = tcpPort;
        connectionOptions.udpPort = udpPort;
        connectionOptions.host = host;
        startClient(connectionOptions);

        server.waitForStop(true);

        waitForThreads(10);
    }

    @Test
    public void connectTcpUdt() throws IOException, InitializationException, SecurityException {
        System.out.println("---- " + "TCP UDT");

        ConnectionOptions connectionOptions = new ConnectionOptions();
        connectionOptions.tcpPort = tcpPort;
        connectionOptions.udtPort = udtPort;

        Server server = startServer(connectionOptions);

        connectionOptions = new ConnectionOptions();
        connectionOptions.tcpPort = tcpPort;
        connectionOptions.udtPort = udtPort;
        connectionOptions.host = host;
        startClient(connectionOptions);

        server.waitForStop(true);

        waitForThreads(10);
    }

    @Test
    public void connectTcpUdpUdt() throws IOException, InitializationException, SecurityException {
        System.out.println("---- " + "TCP UDP UDT");

        ConnectionOptions connectionOptions = new ConnectionOptions();
        connectionOptions.tcpPort = tcpPort;
        connectionOptions.udpPort = udpPort;
        connectionOptions.udtPort = udtPort;

        Server server = startServer(connectionOptions);

        connectionOptions = new ConnectionOptions();
        connectionOptions.tcpPort = tcpPort;
        connectionOptions.udpPort = udpPort;
        connectionOptions.udtPort = udtPort;
        connectionOptions.host = host;

        startClient(connectionOptions);

        server.waitForStop(true);

        waitForThreads(10);
    }

    private Server startServer(ConnectionOptions connectionOptions) throws InitializationException, SecurityException {
        Server server;
        if (connectionOptions != null) {
            server = new Server(connectionOptions);
        } else {
            server = new Server();
        }

        addEndPoint(server);
        server.bind(false);
        server.listeners().add(new Listener<Connection, Object>() {
            Timer timer = new Timer();

            @Override
            public void connected (final Connection connection) {
                timer.schedule(new TimerTask() {
                    @Override
                    public void run () {
                        System.out.println("Disconnecting after 1 second.");
                        connection.close();
                    }
                }, 1000);
            }
        });

        return server;
    }

    private Client startClient(ConnectionOptions connectionOptions) throws InitializationException, SecurityException {
        Client client;
        if (connectionOptions != null) {
            client = new Client(connectionOptions);
        } else {
            client = new Client();
        }
        addEndPoint(client);

        client.listeners().add(new Listener<Connection, Object>() {
            @Override
            public void disconnected (Connection connection) {
                stopEndPoints();
            }
        });
        client.connect(5000);

        return client;
    }
}
