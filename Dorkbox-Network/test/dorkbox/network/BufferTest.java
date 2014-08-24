
package dorkbox.network;


import static org.junit.Assert.fail;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.Listener;
import dorkbox.network.util.SerializationManager;
import dorkbox.network.util.exceptions.InitializationException;
import dorkbox.network.util.exceptions.SecurityException;

public class BufferTest extends BaseTest {
    private static final int OBJ_SIZE = 1024 * 10;

    private volatile int finalCheckAmount = 0;
    private volatile int serverCheck = -1;
    private volatile int clientCheck = -1;

    @Test
    public void manyLargeMessages () throws IOException, InitializationException, SecurityException {
        final int messageCount = 1024;

        ConnectionOptions connectionOptions = new ConnectionOptions();
        connectionOptions.tcpPort = tcpPort;
        connectionOptions.udpPort = udpPort;
        connectionOptions.host = host;

        Server server = new Server(connectionOptions);
        addEndPoint(server);
        server.bind(false);
        register(server.getSerialization());

        server.listeners().add(new Listener<Connection, LargeMessage>() {
            AtomicInteger received = new AtomicInteger();
            AtomicInteger receivedBytes = new AtomicInteger();

            @Override
            public void received (Connection connection, LargeMessage object) {
                System.err.println("Server ack message: " + received.get());
                connection.send().TCP(object);
                receivedBytes.addAndGet(object.bytes.length);

                if (received.incrementAndGet() == messageCount) {
                    System.out.println("Server received all " + messageCount + " messages!");
                    System.out.println("Server received and sent " + receivedBytes.get() + " bytes.");
                    serverCheck = finalCheckAmount - receivedBytes.get();
                    System.out.println("Server missed " + serverCheck + " bytes.");
                    stopEndPoints();
                }
            }
        });

        Client client = new Client(connectionOptions);
        addEndPoint(client);
        register(client.getSerialization());
        client.connect(5000);

        client.listeners().add(new Listener<Connection, LargeMessage>() {
            AtomicInteger received = new AtomicInteger();
            AtomicInteger receivedBytes = new AtomicInteger();

            @Override
            public void received (Connection connection, LargeMessage object) {
                receivedBytes.addAndGet(object.bytes.length);

                int count = received.incrementAndGet();
                //System.out.println("Client received " + count + " messages.");

                if (count == messageCount) {
                    System.out.println("Client received all " + messageCount + " messages!");
                    System.out.println("Client received and sent " + receivedBytes.get() + " bytes.");
                    clientCheck = finalCheckAmount - receivedBytes.get();
                    System.out.println("Client missed " + clientCheck + " bytes.");
                }
            }
        });

        SecureRandom random = new SecureRandom();
        byte[] b = new byte[OBJ_SIZE];
        random.nextBytes(b);

        for (int i = 0; i < messageCount; i++) {
            finalCheckAmount += OBJ_SIZE;
            System.err.println("  Client sending number: " + i);
            client.send().TCP(new LargeMessage(b));
        }
        System.err.println("Client has queued " + messageCount + " messages.");

        waitForThreads();

        if (clientCheck > 0) {
            fail("Client missed " + clientCheck + " bytes.");
        }

        if (serverCheck > 0) {
            fail("Server missed " + serverCheck + " bytes.");
        }
    }

    private void register (SerializationManager kryoMT) {
        kryoMT.register(byte[].class);
        kryoMT.register(LargeMessage.class);
    }

    public static class LargeMessage {
        public byte[] bytes;

        public LargeMessage () {
        }

        public LargeMessage (byte[] bytes) {
            this.bytes = bytes;
        }
    }
}
