package dorkbox.network;


import dorkbox.network.connection.Connection;
import dorkbox.network.connection.KryoCryptoSerializationManager;
import dorkbox.network.connection.Listener;
import dorkbox.network.util.CryptoSerializationManager;
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
        KryoCryptoSerializationManager.DEFAULT = KryoCryptoSerializationManager.DEFAULT();
        register(KryoCryptoSerializationManager.DEFAULT);

        Configuration configuration = new Configuration();
        configuration.tcpPort = tcpPort;
        configuration.host = host;

        Server server = new Server(configuration);
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

        Client client = new Client(configuration);
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
    void register(CryptoSerializationManager kryoMT) {
        kryoMT.register(AMessage.class);
    }

    public static
    class AMessage {
        public
        AMessage() {
        }
    }
}
