/* Copyright (c) 2008, Nathan Sweet
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following
 * conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided with the distribution.
 * - Neither the name of Esoteric Software nor the names of its contributors may be used to endorse or promote products derived
 * from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING,
 * BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT
 * SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package dorkbox.network;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.KryoCryptoSerializationManager;
import dorkbox.network.connection.Listener;
import dorkbox.network.connection.ListenerBridge;
import dorkbox.util.exceptions.InitializationException;
import dorkbox.util.exceptions.SecurityException;

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
        Configuration configuration = new Configuration();
        configuration.tcpPort = tcpPort;
        configuration.host = host;
        configuration.serialization = KryoCryptoSerializationManager.DEFAULT();
        configuration.serialization.register(String[].class);
        configuration.serialization.register(DataClass.class);


        final Server server = new Server(configuration);

        addEndPoint(server);
        server.bind(false);


        final ListenerBridge listeners = server.listeners();
        listeners.add(new Listener.OnConnected<Connection>() {

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
                                DataClass dataClass = new DataClass("Server -> client. Thread #" + index + "  message# " + incrementAndGet,
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
        });

        listeners.add(new Listener.OnMessageReceived<Connection, DataClass>() {
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
            this.clients.add(client);

            addEndPoint(client);
            client.listeners()
                  .add(new Listener.OnMessageReceived<Connection, DataClass>() {
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
