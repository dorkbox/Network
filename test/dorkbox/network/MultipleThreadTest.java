package dorkbox.network;


import dorkbox.network.connection.Connection;
import dorkbox.network.connection.KryoCryptoSerializationManager;
import dorkbox.network.connection.Listener;
import dorkbox.util.exceptions.InitializationException;
import dorkbox.util.exceptions.SecurityException;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    int perClientReceiveTotal = (MultipleThreadTest.this.messageCount * MultipleThreadTest.this.threadCount);
    int serverReceiveTotal = perClientReceiveTotal * MultipleThreadTest.this.clientCount;

    AtomicInteger sent = new AtomicInteger(0);
    AtomicInteger totalClientReceived = new AtomicInteger(0);
    AtomicInteger receivedServer = new AtomicInteger(1);

    ConcurrentHashMap<Integer, DataClass> sentStringsToClientDebug = new ConcurrentHashMap<Integer, DataClass>();

    @Test
    public
    void multipleThreads() throws InitializationException, SecurityException, IOException, InterruptedException {
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

                                      //System.err.println(dataClass.data);
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

                      //System.err.println("server #" + incrementAndGet);
                      if (incrementAndGet == serverReceiveTotal) {
                          System.err.println("Server DONE " + incrementAndGet);
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
                      final int clientIndex = index;
                      final AtomicInteger received = new AtomicInteger(1);

                      @Override
                      public
                      void received(Connection connection, DataClass object) {
                          totalClientReceived.getAndIncrement();
                          int clientLocalCounter = this.received.getAndIncrement();
                          MultipleThreadTest.this.sentStringsToClientDebug.remove(object.index);

                          //System.err.println(object.data);
                          // we finished!!
                          if (clientLocalCounter == perClientReceiveTotal) {
                              //System.err.println("Client #" + clientIndex + " received " + clientLocalCounter + " Sending back " +
                              //                   MultipleThreadTest.this.messageCount + " messages.");

                              // now spam back messages!
                              for (int i = 0; i < MultipleThreadTest.this.messageCount; i++) {
                                  connection.send()
                                            .TCP(new DataClass("Client #" + clientIndex + " -> Server  message " + i, index));
                              }
                          }
                      }
                  });
            client.connect(5000);
        }

        // CLIENT will wait until it's done connecting, but SERVER is async.
        // the ONLY way to safely work in the server is with LISTENERS. Everything else can FAIL, because of it's async nature.

        // our clients should receive messageCount * threadCount * clientCount TOTAL messages
        int totalClientReceivedCountExpected = this.threadCount * this.clientCount * this.messageCount;
        int totalServerReceivedCountExpected = this.clientCount * this.messageCount;

        System.err.println("CLIENT RECEIVES: " + totalClientReceivedCountExpected);
        System.err.println("SERVER RECEIVES: " + totalServerReceivedCountExpected);

        synchronized (this.lock) {
            try {
                this.lock.wait(5 * 1000); // 5 secs
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        if (!this.sentStringsToClientDebug.isEmpty()) {
            System.err.println("MISSED DATA: " + this.sentStringsToClientDebug.size());
            for (Map.Entry<Integer, DataClass> i : this.sentStringsToClientDebug.entrySet()) {
                System.err.println(i.getKey() + " : " + i.getValue().data);
            }
        }

        stopEndPoints();
        assertEquals(totalClientReceivedCountExpected, totalClientReceived.get());
        assertEquals(totalServerReceivedCountExpected, this.receivedServer.get() - 1); // offset by 1 since we start at 1.
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
