
package dorkbox.network;


import static org.junit.Assert.fail;

import java.io.IOException;

import org.junit.Test;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.Ping;
import dorkbox.network.connection.PingListener;
import dorkbox.network.util.exceptions.InitializationException;
import dorkbox.network.util.exceptions.SecurityException;


public class PingTest extends BaseTest {

    private volatile int response = -1;

    // ping prefers the following order:  UDP, UDT, TCP
    @Test
    public void pingTCP() throws IOException, InitializationException, SecurityException {
        this.response = -1;

        ConnectionOptions connectionOptions = new ConnectionOptions();
        connectionOptions.tcpPort = tcpPort;
        connectionOptions.host = host;

        Server server = new Server(connectionOptions);
        server.disableRemoteKeyValidation();
        addEndPoint(server);
        server.bind(false);

        // ----

        Client client = new Client(connectionOptions);
        client.disableRemoteKeyValidation();
        addEndPoint(client);

        client.connect(5000);

        System.err.println("Testing TCP ping");
        for (int i=0;i<10;i++) {
            this.response = client.send().ping().getResponse();
            System.err.println("Ping: " + this.response);
        }

        stopEndPoints();
        if (this.response == -1) {
            fail();
        }
    }

    @Test
    public void pingTCP_testListeners1() throws IOException, InitializationException, SecurityException {
        this.response = -1;

        ConnectionOptions connectionOptions = new ConnectionOptions();
        connectionOptions.tcpPort = tcpPort;
        connectionOptions.host = host;

        Server server = new Server(connectionOptions);
        server.disableRemoteKeyValidation();
        addEndPoint(server);
        server.bind(false);

        // ----

        Client client = new Client(connectionOptions);
        client.disableRemoteKeyValidation();
        addEndPoint(client);

        client.connect(5000);

        System.err.println("Testing TCP ping with multi callback");

        final PingListener<Connection> pingListener = new PingListener<Connection>() {
            volatile int count = 0;

            @Override
            public void response(Connection connection, int pingResponseTime) {
                System.err.println("Ping: " + pingResponseTime);

                if (this.count++ < 10) {
                    connection.send().ping().addListener(this);
                } else {
                    PingTest.this.response = pingResponseTime;
                    stopEndPoints();
                }
            }
        };

        //  alternate way to register for the receipt of a one-off ping response
        // doesn't matter how many times this is called. If there is a PING waiting, then it's overwritten
        Ping ping = client.send().ping();
        ping.addListener(pingListener);

        waitForThreads();

        if (this.response == -1) {
            fail();
        }
    }

    @Test
    public void pingTCP_testListeners2() throws IOException, InitializationException, SecurityException {
        this.response = -1;

        ConnectionOptions connectionOptions = new ConnectionOptions();
        connectionOptions.tcpPort = tcpPort;
        connectionOptions.host = host;

        Server server = new Server(connectionOptions);
        server.disableRemoteKeyValidation();
        addEndPoint(server);
        server.bind(false);

        // ----

        Client client = new Client(connectionOptions);
        client.disableRemoteKeyValidation();
        addEndPoint(client);

        client.connect(5000);

        System.err.println("Testing TCP ping with single callback");

        final PingListener<Connection> pingListener = new PingListener<Connection>() {
            @Override
            public void response(Connection connection, int pingResponseTime) {
                System.err.println("Ping: " + pingResponseTime);
                PingTest.this.response = pingResponseTime;
                stopEndPoints();
            }
        };


        //  alternate way to register for the receipt of a one-off ping response
        // doesn't matter how many times this is called. If there is a PING waiting, then it's overwritten
        Ping ping = client.send().ping();
        ping.addListener(pingListener);

        waitForThreads();

        if (this.response == -1) {
            fail();
        }
    }

    // ping prefers the following order:  UDP, UDT, TCP
    @Test
    public void pingUDP() throws IOException, InitializationException, SecurityException {
        this.response = -1;

        ConnectionOptions connectionOptions = new ConnectionOptions();
        connectionOptions.tcpPort = tcpPort;
        connectionOptions.udpPort = udpPort;
        connectionOptions.host = host;


        Server server = new Server(connectionOptions);
        server.disableRemoteKeyValidation();
        addEndPoint(server);
        server.bind(false);

        // ----

        Client client = new Client(connectionOptions);
        client.disableRemoteKeyValidation();
        addEndPoint(client);

        client.connect(5000);

        System.err.println("Testing UDP ping");
        for (int i=0;i<10;i++) {
            this.response = client.send().ping().getResponse();
            System.err.println("Ping: " + this.response);
        }

        stopEndPoints();

        if (this.response == -1) {
            fail();
        }
    }


    // ping prefers the following order:  UDP, UDT, TCP
    @Test
    public void pingUDT() throws IOException, InitializationException, SecurityException {
        this.response = -1;

        ConnectionOptions connectionOptions = new ConnectionOptions();
        connectionOptions.tcpPort = tcpPort;
        connectionOptions.udtPort = udtPort;
        connectionOptions.host = host;

        Server server = new Server(connectionOptions);
        server.disableRemoteKeyValidation();
        addEndPoint(server);
        server.bind(false);

        // ----

        Client client = new Client(connectionOptions);
        client.disableRemoteKeyValidation();
        addEndPoint(client);

        client.connect(5000);

        System.err.println("Testing UDT ping");
        for (int i=0;i<10;i++) {
            this.response = client.send().ping().getResponse();
            System.err.println("Ping: " + this.response);
        }

        stopEndPoints();

        if (this.response == -1) {
            fail();
        }
    }
}
