
package dorkbox.network;


import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.Listener;
import dorkbox.network.util.SerializationManager;
import dorkbox.network.util.exceptions.InitializationException;
import dorkbox.network.util.exceptions.SecurityException;

public class ClientSendTest extends BaseTest {

    private AtomicBoolean checkPassed = new AtomicBoolean(false);

    @Test
    public void sendDataFromClientClass () throws IOException, InitializationException, SecurityException {
        ConnectionOptions connectionOptions = new ConnectionOptions();
        connectionOptions.tcpPort = tcpPort;
        connectionOptions.host = host;

        Server server = new Server(connectionOptions);
        server.disableRemoteKeyValidation();
        addEndPoint(server);
        server.bind(false);
        register(server.getSerialization());

        server.listeners().add(new Listener<AMessage>() {
            @Override
            public void received (Connection connection, AMessage object) {
                System.err.println("Server received message from client. Bouncing back.");
                connection.send().TCP(object);
            }
        });

        Client client = new Client(connectionOptions);
        client.disableRemoteKeyValidation();
        addEndPoint(client);
        register(client.getSerialization());
        client.connect(5000);

        client.listeners().add(new Listener<AMessage>() {
            @Override
            public void received (Connection connection, AMessage object) {
                ClientSendTest.this.checkPassed.set(true);
                stopEndPoints();
            }
        });

        client.send().TCP(new AMessage());

        waitForThreads();

        if (!this.checkPassed.get()) {
            fail("Client and server failed to send messages!");
        }
    }

    private void register (SerializationManager kryoMT) {
        kryoMT.register(AMessage.class);
    }

    public static class AMessage {
        public AMessage () {
        }
    }
}
