package dorkbox.network;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.KryoCryptoSerializationManager;
import dorkbox.network.connection.Listener;
import dorkbox.util.exceptions.InitializationException;
import dorkbox.util.exceptions.SecurityException;
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
        KryoCryptoSerializationManager.DEFAULT = KryoCryptoSerializationManager.DEFAULT();
        KryoCryptoSerializationManager.DEFAULT.register(String[].class);

        Configuration configuration1 = new Configuration();
        configuration1.tcpPort = tcpPort;
        configuration1.udpPort = udpPort;
        configuration1.localChannelName = "chan1";

        Server server1 = new Server(configuration1);
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

        Configuration configuration2 = new Configuration();
        configuration2.tcpPort = tcpPort + 1;
        configuration2.udpPort = udpPort + 1;
        configuration2.localChannelName = "chan2";

        Server server2 = new Server(configuration2);
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

        configuration1.localChannelName = null;
        configuration1.host = host;

        Client client1 = new Client(configuration1);
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


        configuration2.localChannelName = null;
        configuration2.host = host;

        Client client2 = new Client(configuration2);
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
