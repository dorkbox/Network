package dorkbox.network;


import dorkbox.network.connection.Connection;
import dorkbox.network.connection.KryoCryptoSerializationManager;
import dorkbox.network.connection.Listener;
import dorkbox.network.util.CryptoSerializationManager;
import dorkbox.util.exceptions.InitializationException;
import dorkbox.util.exceptions.SecurityException;
import org.junit.Test;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.fail;

public
class LargeResizeBufferTest extends BaseTest {
    private static final int OBJ_SIZE = 1024 * 100;

    private volatile int finalCheckAmount = 0;
    private volatile int serverCheck = -1;
    private volatile int clientCheck = -1;

    @Test
    public
    void manyLargeMessages() throws InitializationException, SecurityException, IOException, InterruptedException {
        KryoCryptoSerializationManager.DEFAULT = KryoCryptoSerializationManager.DEFAULT();
        register(KryoCryptoSerializationManager.DEFAULT);

        final int messageCount = 1024;

        Configuration configuration = new Configuration();
        configuration.tcpPort = tcpPort;
        configuration.udpPort = udpPort;
        configuration.host = host;

        Server server = new Server(configuration);
        server.disableRemoteKeyValidation();
        addEndPoint(server);
        server.bind(false);

        server.listeners()
              .add(new Listener<LargeMessage>() {
                  AtomicInteger received = new AtomicInteger();
                  AtomicInteger receivedBytes = new AtomicInteger();

                  @Override
                  public
                  void received(Connection connection, LargeMessage object) {
                      // System.err.println("Server ack message: " + received.get());

                      connection.send()
                                .TCP(object);
                      this.receivedBytes.addAndGet(object.bytes.length);

                      if (this.received.incrementAndGet() == messageCount) {
                          System.out.println("Server received all " + messageCount + " messages!");
                          System.out.println("Server received and sent " + this.receivedBytes.get() + " bytes.");
                          LargeResizeBufferTest.this.serverCheck = LargeResizeBufferTest.this.finalCheckAmount - this.receivedBytes.get();
                          System.out.println("Server missed " + LargeResizeBufferTest.this.serverCheck + " bytes.");
                          stopEndPoints();
                      }
                  }
              });

        Client client = new Client(configuration);
        client.disableRemoteKeyValidation();
        addEndPoint(client);

        client.listeners()
              .add(new Listener<LargeMessage>() {
                  AtomicInteger received = new AtomicInteger();
                  AtomicInteger receivedBytes = new AtomicInteger();

                  @Override
                  public
                  void received(Connection connection, LargeMessage object) {
                      this.receivedBytes.addAndGet(object.bytes.length);

                      int count = this.received.getAndIncrement();
                      // System.out.println("Client received message: " + count);

                      if (count == messageCount) {
                          System.out.println("Client received all " + messageCount + " messages!");
                          System.out.println("Client received and sent " + this.receivedBytes.get() + " bytes.");
                          LargeResizeBufferTest.this.clientCheck = LargeResizeBufferTest.this.finalCheckAmount - this.receivedBytes.get();
                          System.out.println("Client missed " + LargeResizeBufferTest.this.clientCheck + " bytes.");
                      }
                  }
              });
        client.connect(5000);

        SecureRandom random = new SecureRandom();

        System.err.println("  Client sending " + messageCount + " messages");
        for (int i = 0; i < messageCount; i++) {
            this.finalCheckAmount += OBJ_SIZE; // keep increasing size

            byte[] b = new byte[OBJ_SIZE];
            random.nextBytes(b);

            // set some of the bytes to be all `244`, just so some compression can occur (to test that as well)
            for (int j = 0; j < 400; j++) {
                b[j] = (byte) 244;
            }

//            System.err.println("Sending " + b.length + " bytes");
            client.send()
                  .TCP(new LargeMessage(b));
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

    private
    void register(CryptoSerializationManager kryoMT) {
        kryoMT.register(byte[].class);
        kryoMT.register(LargeMessage.class);
    }

    public static
    class LargeMessage {
        public byte[] bytes;

        public
        LargeMessage() {
        }

        public
        LargeMessage(byte[] bytes) {
            this.bytes = bytes;
        }
    }
}
