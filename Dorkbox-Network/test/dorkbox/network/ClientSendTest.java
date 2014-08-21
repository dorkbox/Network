
package dorkbox.network;


import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.Listener;
import dorkbox.network.util.InitializationException;
import dorkbox.network.util.SecurityException;
import dorkbox.network.util.SerializationManager;

public class ClientSendTest extends BaseTest {

    private AtomicBoolean checkPassed = new AtomicBoolean(false);

    @Test
    public void sendDataFromClientClass () throws IOException, InitializationException, SecurityException {
        ConnectionOptions connectionOptions = new ConnectionOptions();
        connectionOptions.tcpPort = tcpPort;
        connectionOptions.host = host;

        Server server = new Server(connectionOptions);
        addEndPoint(server);
        server.bind(false);
        register(server.getSerialization());

        server.listeners().add(new Listener<Connection, AMessage>() {
            @Override
            public void received (Connection connection, AMessage object) {
                System.err.println("Server received message from client. Bouncing back.");
                connection.send().TCP(object);
            }
        });

        Client client = new Client(connectionOptions);
        addEndPoint(client);
        register(client.getSerialization());
        client.connect(5000);

        client.listeners().add(new Listener<Connection, AMessage>() {
            @Override
            public void received (Connection connection, AMessage object) {
                checkPassed.set(true);
                stopEndPoints();
            }
        });

        client.send().TCP(new AMessage());

        waitForThreads();

        if (!checkPassed.get()) {
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
