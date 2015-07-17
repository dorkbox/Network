package dorkbox.network;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.Listener;
import dorkbox.network.util.KryoConnectionSerializationManager;
import dorkbox.network.util.exceptions.InitializationException;
import dorkbox.network.util.exceptions.SecurityException;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.fail;

public
class MultipleServerTest extends BaseTest {
    AtomicInteger received = new AtomicInteger();

    @Test
    public
    void multipleServers() throws InitializationException, SecurityException, IOException {

        ConnectionOptions connectionOptions1 = new ConnectionOptions();
        connectionOptions1.tcpPort = tcpPort;
        connectionOptions1.udpPort = udpPort;
        connectionOptions1.localChannelName = "chan1";
        connectionOptions1.serializationManager = KryoConnectionSerializationManager.DEFAULT();
        connectionOptions1.serializationManager.register(String[].class);

        Server server1 = new Server(connectionOptions1);
        server1.disableRemoteKeyValidation();
        addEndPoint(server1);

        server1.bind(false);
        server1.listeners()
               .add(new Listener<String>() {
                   @Override
                   public
                   void received(Connection connection, String object) {
                       if (!object.equals("client1")) {
                           fail();
                       }
                       if (MultipleServerTest.this.received.incrementAndGet() == 2) {
                           stopEndPoints();
                       }
                   }
               });

        ConnectionOptions connectionOptions2 = new ConnectionOptions();
        connectionOptions2.tcpPort = tcpPort + 1;
        connectionOptions2.udpPort = udpPort + 1;
        connectionOptions2.localChannelName = "chan2";
        connectionOptions2.serializationManager = KryoConnectionSerializationManager.DEFAULT();
        connectionOptions2.serializationManager.register(String[].class);

        Server server2 = new Server(connectionOptions2);
        server2.disableRemoteKeyValidation();

        addEndPoint(server2);
        server2.bind(false);
        server2.listeners()
               .add(new Listener<String>() {
                   @Override
                   public
                   void received(Connection connection, String object) {
                       if (!object.equals("client2")) {
                           fail();
                       }
                       if (MultipleServerTest.this.received.incrementAndGet() == 2) {
                           stopEndPoints();
                       }
                   }
               });

        // ----

        connectionOptions1.localChannelName = null;
        connectionOptions1.host = host;

        Client client1 = new Client(connectionOptions1);
        client1.disableRemoteKeyValidation();
        addEndPoint(client1);
        client1.listeners()
               .add(new Listener<String>() {
                   @Override
                   public
                   void connected(Connection connection) {
                       connection.send()
                                 .TCP("client1");
                   }
               });
        client1.connect(5000);


        connectionOptions2.localChannelName = null;
        connectionOptions2.host = host;

        Client client2 = new Client(connectionOptions2);
        client2.disableRemoteKeyValidation();
        addEndPoint(client2);
        client2.listeners()
               .add(new Listener<String>() {
                   @Override
                   public
                   void connected(Connection connection) {
                       connection.send()
                                 .TCP("client2");
                   }
               });
        client2.connect(5000);

        waitForThreads(30);
    }
}
