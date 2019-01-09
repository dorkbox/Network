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
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.Listener;
import dorkbox.network.connection.Listeners;
import dorkbox.util.exceptions.SecurityException;

// NOTE: UDP is unreliable, EVEN ON LOOPBACK! So this can fail with UDP. TCP will never fail.
public
class ReconnectTest extends BaseTest {
    private final AtomicInteger receivedCount = new AtomicInteger(0);

    @Test
    public
    void socketReuseUDP() throws IOException, SecurityException {
        socketReuse(false, true);
    }

    @Test
    public
    void socketReuseTCP() throws IOException, SecurityException {
        socketReuse(true, false);
    }

    @Test
    public
    void socketReuseTCPUDP() throws IOException, SecurityException {
        socketReuse(true, true);
    }

    private
    void socketReuse(final boolean useTCP, final boolean useUDP) throws SecurityException, IOException {
        receivedCount.set(0);

        Configuration configuration = new Configuration();
        configuration.host = host;

        if (useTCP) {
            configuration.tcpPort = tcpPort;
        }

        if (useUDP) {
            configuration.udpPort = udpPort;
        }


        Server server = new Server(configuration);
        addEndPoint(server);
        final Listeners listeners = server.listeners();
        listeners.add(new Listener.OnConnected<Connection>() {
            @Override
            public
            void connected(Connection connection) {
                if (useTCP) {
                    connection.send()
                              .TCP("-- TCP from server");
                }
                if (useUDP) {
                    connection.send()
                              .UDP("-- UDP from server");
                }
            }
        });
        listeners.add(new Listener.OnMessageReceived<Connection, String>() {
            @Override
            public
            void received(Connection connection, String object) {
                int incrementAndGet = ReconnectTest.this.receivedCount.incrementAndGet();
                System.out.println("----- <S " + connection + "> " + incrementAndGet + " : " + object);

                synchronized (receivedCount) {
                    receivedCount.notifyAll();
                }
            }
        });
        server.bind(false);


        // ----

        Client client = new Client(configuration);
        addEndPoint(client);
        final Listeners listeners1 = client.listeners();
        listeners1.add(new Listener.OnConnected<Connection>() {
            @Override
            public
            void connected(Connection connection) {
                if (useTCP) {
                    connection.send()
                              .TCP("-- TCP from client");
                }
                if (useUDP) {
                    connection.send()
                              .UDP("-- UDP from client");
                }
            }
        });
        listeners1.add(new Listener.OnMessageReceived<Connection, String>() {
            @Override
            public
            void received(Connection connection, String object) {
                int incrementAndGet = ReconnectTest.this.receivedCount.incrementAndGet();
                System.out.println("----- <C " + connection + "> " + incrementAndGet + " : " + object);

                synchronized (receivedCount) {
                    receivedCount.notifyAll();
                }
            }
        });



        int count = 100;
        int initialCount = 2;
        if (useTCP && useUDP) {
            initialCount += 2;
        }
        for (int i = 1; i < count + 1; i++) {
            System.out.println(".....");
            client.connect(5000);


            int waitingRetryCount = 20;
            int target = i * initialCount;

            synchronized (receivedCount) {
                while (this.receivedCount.get() != target) {
                    if (waitingRetryCount-- < 0) {
                        System.err.println("Aborting unit test... wrong count!");
                        if (useUDP) {
                            System.err.println("NOTE: UDP can fail, even on loopback!");
                        }
                        stopEndPoints();
                        assertEquals(target, this.receivedCount.get());
                    }
                    try {
                        receivedCount.wait(1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }

            client.close();
            System.out.println(".....");
        }

        assertEquals(count * initialCount, this.receivedCount.get());

        stopEndPoints();
        waitForThreads(10);
    }

    @Test
    public
    void localReuse() throws SecurityException, IOException {
        receivedCount.set(0);

        Server server = new Server();
        addEndPoint(server);
        server.listeners()
              .add(new Listener.OnConnected<Connection>() {
                  @Override
                  public
                  void connected(Connection connection) {
                      connection.send()
                                .self("-- LOCAL from server");
                  }
              });
        server.listeners()
              .add(new Listener.OnMessageReceived<Connection, String>() {
                  @Override
                  public
                  void received(Connection connection, String object) {
                      int incrementAndGet = ReconnectTest.this.receivedCount.incrementAndGet();
                      System.out.println("----- <S " + connection + "> " + incrementAndGet + " : " + object);
                  }
              });

        // ----

        Client client = new Client();
        addEndPoint(client);
        client.listeners()
              .add(new Listener.OnConnected<Connection>() {
                  @Override
                  public
                  void connected(Connection connection) {
                      connection.send()
                                .self("-- LOCAL from client");
                  }
              });

        client.listeners()
              .add(new Listener.OnMessageReceived<Connection, String>() {
                  @Override
                  public
                  void received(Connection connection, String object) {
                      int incrementAndGet = ReconnectTest.this.receivedCount.incrementAndGet();
                      System.out.println("----- <C " + connection + "> " + incrementAndGet + " : " + object);
                  }
              });

        server.bind(false);
        int count = 10;
        for (int i = 1; i < count + 1; i++) {
            client.connect(5000);

            int target = i * 2;
            while (this.receivedCount.get() != target) {
                System.out.println("----- Waiting...");
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                }
            }

            client.close();
        }

        assertEquals(count * 2, this.receivedCount.get());

        stopEndPoints();
        waitForThreads(10);
    }
}
