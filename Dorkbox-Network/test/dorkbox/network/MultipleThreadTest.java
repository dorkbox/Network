package dorkbox.network;


import dorkbox.network.connection.Connection;
import dorkbox.network.connection.KryoCryptoSerializationManager;
import dorkbox.network.connection.Listener;
import dorkbox.network.util.exceptions.InitializationException;
import dorkbox.network.util.exceptions.SecurityException;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

public
class MultipleThreadTest extends BaseTest {
    private final Object lock = new Object();
    private final int messageCount = 150;
    private final int threadCount = 15;
    private final int clientCount = 13;

    private final List<Client> clients = new ArrayList<Client>(this.clientCount);

    AtomicInteger sent = new AtomicInteger(0);
    AtomicInteger totalClientCounter = new AtomicInteger(1);
    AtomicInteger receivedServer = new AtomicInteger(1);
    ConcurrentHashMap<Integer, DataClass> sentStringsToClientDebug = new ConcurrentHashMap<Integer, DataClass>();

    @Test
    public
    void multipleThreads() throws InitializationException, SecurityException, IOException {
        KryoCryptoSerializationManager.DEFAULT = KryoCryptoSerializationManager.DEFAULT();
        KryoCryptoSerializationManager.DEFAULT.register(String[].class);
        KryoCryptoSerializationManager.DEFAULT.register(DataClass.class);

        Configuration configuration = new Configuration();
        configuration.tcpPort = tcpPort;
        configuration.host = host;

        final Server server = new Server(configuration);
        server.disableRemoteKeyValidation();

        addEndPoint(server);
        server.bind(false);
        server.listeners()
              .add(new Listener<DataClass>() {
                  private final int total = MultipleThreadTest.this.messageCount * MultipleThreadTest.this.clientCount;

                  @Override
                  public
                  void connected(final Connection connection) {
                      System.err.println("Client connected to server.");

                      // kickoff however many threads we need, and send data to the client.
                      for (int i = 1; i <= MultipleThreadTest.this.threadCount; i++) {
                          final int index = i;
                          new Thread() {
                              @Override
                              public
                              void run() {
                                  for (int i = 1; i <= MultipleThreadTest.this.messageCount; i++) {
                                      int incrementAndGet = MultipleThreadTest.this.sent.getAndIncrement();
                                      DataClass dataClass = new DataClass(
                                                      "Server -> client. Thread #" + index + "  message# " + incrementAndGet,
                                                      incrementAndGet);

                                      MultipleThreadTest.this.sentStringsToClientDebug.put(incrementAndGet, dataClass);
                                      connection.send()
                                                .TCP(dataClass)
                                                .flush();
                                  }
                              }
                          }.start();
                      }
                  }


                  @Override
                  public
                  void received(Connection connection, DataClass object) {
                      int incrementAndGet = MultipleThreadTest.this.receivedServer.getAndIncrement();


                      if (incrementAndGet == total) {
                          System.err.println("Server DONE " + incrementAndGet);
                          // note. this is getting called BEFORE it's ready?
                          stopEndPoints();
                          synchronized (MultipleThreadTest.this.lock) {
                              MultipleThreadTest.this.lock.notifyAll();
                          }
                      }
                  }
              });

        // ----

        for (int i = 1; i <= this.clientCount; i++) {
            final int index = i;

            Client client = new Client(configuration);
            client.disableRemoteKeyValidation();

            this.clients.add(client);

            addEndPoint(client);
            client.listeners()
                  .add(new Listener<DataClass>() {
                      AtomicInteger received = new AtomicInteger(1);
                      private final int total = MultipleThreadTest.this.messageCount * MultipleThreadTest.this.clientCount;

                      @Override
                      public
                      void connected(Connection connection) {
                          System.err.println("Client #" + index + " connected.");
                      }

                      @Override
                      public
                      void received(Connection connection, DataClass object) {
                          int clientLocalCounter = this.received.getAndIncrement();
                          MultipleThreadTest.this.sentStringsToClientDebug.remove(object.index);

                          // we finished!!
                          if (clientLocalCounter == total) {
                              System.err.println("Client #" + index + " received " + clientLocalCounter + " (" +
                                                 MultipleThreadTest.this.totalClientCounter.getAndIncrement() + ")  Sending back " +
                                                 MultipleThreadTest.this.messageCount + " messages.");

                              // now spam back messages!
                              for (int i = 0; i < MultipleThreadTest.this.messageCount; i++) {
                                  connection.send()
                                            .TCP(new DataClass("Client #" + index + " -> Server  message " + i, index));
                              }
                          }
                      }
                  });
            client.connect(5000);
        }

        // CLIENT will wait until it's done connecting, but SERVER is async.
        // the ONLY way to safely work in the server is with LISTENERS. Everything else can FAIL, because of it's async. nature.

        // our clients should receive messageCount * threadCount * clientCount TOTAL messages
        System.err.println("SEND COUNTS: " + this.threadCount * this.clientCount * this.messageCount + " and then " +
                           this.messageCount * this.clientCount + " total messages");

        synchronized (this.lock) {
            try {
                this.lock.wait(150 * 1000); // 15 secs
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        if (!this.sentStringsToClientDebug.isEmpty()) {
            System.err.println("MISSED DATA: " + this.sentStringsToClientDebug.size());
            for (Entry<Integer, DataClass> i : this.sentStringsToClientDebug.entrySet()) {
                System.err.println(i.getKey() + " : " + i.getValue().data);
            }
        }

        stopEndPoints();
        assertEquals(this.messageCount * this.clientCount, this.receivedServer.get() - 1); // offset by 1 since we start at 1.
    }

    public static
    class DataClass {
        public String data;
        public Integer index;

        public
        DataClass() {
        }

        public
        DataClass(String data, Integer index) {
            this.data = data;
            this.index = index;
        }
    }
}
