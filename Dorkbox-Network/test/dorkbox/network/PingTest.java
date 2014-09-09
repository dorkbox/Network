
package dorkbox.network;


import static org.junit.Assert.fail;

import java.io.IOException;

import org.junit.Test;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.Listener;
import dorkbox.network.connection.ping.PingMessage;
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
        addEndPoint(server);
        server.bind(false);

        // ----

        Client client = new Client(connectionOptions);
        addEndPoint(client);


        client.listeners().add(new Listener<Connection, PingMessage>() {
            int count = 0;

            @Override
            public void connected(Connection connection) {
                System.err.println("Testing TCP ping");

                for (int i=0;i<10;i++) {
                    int response2 = connection.send().ping().getResponse();
                    System.err.println("Ping B roundtime: " + response2);
                }
            }

//            @Override
//            public void received(Connection connection, PingMessage ping) {
//                response = ping.time;
//                System.err.println("Ping return time: " + response);
//
//                if (count++ < 10) {
//                    connection.send().ping();
//                } else {
//                    stopEndPoints();
//                }
//            }
        });
        client.connect(5000);


        for (int i=0;i<10;i++) {
            int response2 = client.ping().getResponse();
            System.err.println("Ping A roundtime: " + response2);
        }

        // alternate way to register for the receipt of a one-off ping response
//        PingFuture ping = connection.ping();
//        ping.addListener(new ChannelFutureListener() {
//            int count = 0;
//                @Override
//                public void operationComplete(ChannelFuture future) throws Exception {
//                    response = ((PingFuture)future).getResponseUninterruptibly();
//                    System.err.println("Ping return time: " + response);
//
//                    if (count++ < 10) {
//                        connection.ping();
//                    } else {
//                        stopEndPoints();
//                    }
//                }
//            });

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
        addEndPoint(server);
        server.bind(false);

        // ----

        Client client = new Client(connectionOptions);
        addEndPoint(client);


        client.listeners().add(new Listener<Connection, PingMessage>() {
            int count = 0;

            @Override
            public void connected(Connection connection) {
                System.err.println("Testing UDP ping");
            }
        });
        client.connect(5000);

        client.ping();
        // alternate way to register for the receipt of a one-off ping response
//        PingFuture ping = connection.ping();
//        ping.addListener(new ChannelFutureListener() {
//            int count = 0;
//                @Override
//                public void operationComplete(ChannelFuture future) throws Exception {
//                    response = ((PingFuture)future).getResponseUninterruptibly();
//                    System.err.println("Ping return time: " + response);
//
//                    if (count++ < 10) {
//                        connection.ping();
//                    } else {
//                        stopEndPoints();
//                    }
//                }
//            });

        waitForThreads();

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
        addEndPoint(server);
        server.bind(false);

        // ----

        Client client = new Client(connectionOptions);
        addEndPoint(client);


        client.listeners().add(new Listener<Connection, PingMessage>() {
            int count = 0;

            @Override
            public void connected(Connection connection) {
                System.err.println("Testing UDT ping");
            }

//            @Override
//            public void received(Connection connection, PingMessage ping) {
//                PingTest.this.response = ping.time;
//                System.err.println("Ping return time: " + PingTest.this.response);
//
//                if (this.count++ < 10) {
//                    connection.send().ping();
//                } else {
//                    stopEndPoints();
//                }
//            }
        });
        client.connect(5000);

        client.ping();
        // alternate way to register for the receipt of a one-off ping response
//        PingFuture ping = connection.ping();
//        ping.addListener(new ChannelFutureListener() {
//            int count = 0;
//                @Override
//                public void operationComplete(ChannelFuture future) throws Exception {
//                    response = ((PingFuture)future).getResponseUninterruptibly();
//                    System.err.println("Ping return time: " + response);
//
//                    if (count++ < 10) {
//                        connection.ping();
//                    } else {
//                        stopEndPoints();
//                    }
//                }
//            });

        waitForThreads();

        if (this.response == -1) {
            fail();
        }
    }
}
