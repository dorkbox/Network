package dorkbox.network;


import dorkbox.network.connection.Connection;
import dorkbox.network.connection.Listener;
import dorkbox.network.util.ConnectionSerializationManager;
import dorkbox.network.util.KryoConnectionSerializationManager;
import dorkbox.network.util.exceptions.InitializationException;
import dorkbox.network.util.exceptions.SecurityException;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.fail;

public
class ClientSendTest extends BaseTest {

    private AtomicBoolean checkPassed = new AtomicBoolean(false);

    @Test
    public
    void sendDataFromClientClass() throws InitializationException, SecurityException, IOException {
        ConnectionOptions connectionOptions = new ConnectionOptions();
        connectionOptions.tcpPort = tcpPort;
        connectionOptions.host = host;
        connectionOptions.serializationManager = KryoConnectionSerializationManager.DEFAULT();
        register(connectionOptions.serializationManager);

        Server server = new Server(connectionOptions);
        server.disableRemoteKeyValidation();
        addEndPoint(server);
        server.bind(false);

        server.listeners()
              .add(new Listener<AMessage>() {
                  @Override
                  public
                  void received(Connection connection, AMessage object) {
                      System.err.println("Server received message from client. Bouncing back.");
                      connection.send()
                                .TCP(object);
                  }
              });

        Client client = new Client(connectionOptions);
        client.disableRemoteKeyValidation();
        addEndPoint(client);
        client.connect(5000);

        client.listeners()
              .add(new Listener<AMessage>() {
                  @Override
                  public
                  void received(Connection connection, AMessage object) {
                      ClientSendTest.this.checkPassed.set(true);
                      stopEndPoints();
                  }
              });

        client.send()
              .TCP(new AMessage());

        waitForThreads();

        if (!this.checkPassed.get()) {
            fail("Client and server failed to send messages!");
        }
    }

    private static
    void register(ConnectionSerializationManager kryoMT) {
        kryoMT.register(AMessage.class);
    }

    public static
    class AMessage {
        public
        AMessage() {
        }
    }
}
