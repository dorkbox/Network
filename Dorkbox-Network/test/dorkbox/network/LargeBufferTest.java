
package dorkbox.network;


import dorkbox.network.connection.Connection;
import dorkbox.network.connection.Listener;
import dorkbox.network.util.ConnectionSerializationManager;
import dorkbox.network.util.KryoConnectionSerializationManager;
import dorkbox.network.util.exceptions.InitializationException;
import dorkbox.network.util.exceptions.SecurityException;
import org.junit.Test;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.fail;

public class LargeBufferTest extends BaseTest {
    private static final int OBJ_SIZE = 1024 * 10;

    private volatile int finalCheckAmount = 0;
    private volatile int serverCheck = -1;
    private volatile int clientCheck = -1;

    @Test
    public void manyLargeMessages () throws InitializationException, SecurityException, IOException {
        final int messageCount = 1024;

        ConnectionOptions connectionOptions = new ConnectionOptions();
        connectionOptions.tcpPort = tcpPort;
        connectionOptions.udpPort = udpPort;
        connectionOptions.host = host;
        connectionOptions.serializationManager = KryoConnectionSerializationManager.DEFAULT();
        register(connectionOptions.serializationManager);

        Server server = new Server(connectionOptions);
        server.disableRemoteKeyValidation();
        addEndPoint(server);
        server.bind(false);

        server.listeners().add(new Listener<LargeMessage>() {
            AtomicInteger received = new AtomicInteger();
            AtomicInteger receivedBytes = new AtomicInteger();

            @Override
            public void received (Connection connection, LargeMessage object) {
//                System.err.println("Server ack message: " + received.get());
                connection.send().TCP(object);
                this.receivedBytes.addAndGet(object.bytes.length);

                if (this.received.incrementAndGet() == messageCount) {
                    System.out.println("Server received all " + messageCount + " messages!");
                    System.out.println("Server received and sent " + this.receivedBytes.get() + " bytes.");
                    LargeBufferTest.this.serverCheck = LargeBufferTest.this.finalCheckAmount - this.receivedBytes.get();
                    System.out.println("Server missed " + LargeBufferTest.this.serverCheck + " bytes.");
                    stopEndPoints();
                }
            }
        });

        Client client = new Client(connectionOptions);
        client.disableRemoteKeyValidation();
        addEndPoint(client);
        client.connect(5000);

        client.listeners().add(new Listener<LargeMessage>() {
            AtomicInteger received = new AtomicInteger();
            AtomicInteger receivedBytes = new AtomicInteger();

            @Override
            public void received (Connection connection, LargeMessage object) {
                this.receivedBytes.addAndGet(object.bytes.length);

                int count = this.received.incrementAndGet();
                //System.out.println("Client received " + count + " messages.");

                if (count == messageCount) {
                    System.out.println("Client received all " + messageCount + " messages!");
                    System.out.println("Client received and sent " + this.receivedBytes.get() + " bytes.");
                    LargeBufferTest.this.clientCheck = LargeBufferTest.this.finalCheckAmount - this.receivedBytes.get();
                    System.out.println("Client missed " + LargeBufferTest.this.clientCheck + " bytes.");
                }
            }
        });

        SecureRandom random = new SecureRandom();
        byte[] b = new byte[OBJ_SIZE];
        random.nextBytes(b);

        for (int i = 0; i < messageCount; i++) {
            this.finalCheckAmount += OBJ_SIZE;
            System.err.println("  Client sending number: " + i);
            client.send().TCP(new LargeMessage(b));
        }
        System.err.println("Client has queued " + messageCount + " messages.");

        waitForThreads();

        if (this.clientCheck > 0) {
            fail("Client missed " + this.clientCheck + " bytes.");
        }

        if (this.serverCheck > 0) {
            fail("Server missed " + this.serverCheck + " bytes.");
        }
    }

    private void register (ConnectionSerializationManager kryoMT) {
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
