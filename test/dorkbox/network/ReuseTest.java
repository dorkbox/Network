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

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.Listener;
import dorkbox.util.exceptions.InitializationException;
import dorkbox.util.exceptions.SecurityException;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

public
class ReuseTest extends BaseTest {
    AtomicInteger serverCount;
    AtomicInteger clientCount;

    @Test
    public
    void socketReuse() throws InitializationException, SecurityException, IOException, InterruptedException {
        this.serverCount = new AtomicInteger(0);
        this.clientCount = new AtomicInteger(0);

        Configuration configuration = new Configuration();
        configuration.tcpPort = tcpPort;
        configuration.udpPort = udpPort;
        configuration.host = host;

        Server server = new Server(configuration);
        server.disableRemoteKeyValidation();
        addEndPoint(server);
        server.listeners()
              .add(new Listener<String>() {
                  @Override
                  public
                  void connected(Connection connection) {
                      connection.send()
                                .TCP("-- TCP from server");
                      connection.send()
                                .UDP("-- UDP from server");
                  }

                  @Override
                  public
                  void received(Connection connection, String object) {
                      int incrementAndGet = ReuseTest.this.serverCount.incrementAndGet();
                      System.err.println("<S " + connection + "> " + incrementAndGet + " : " + object);
                  }
              });

        // ----

        Client client = new Client(configuration);
        client.disableRemoteKeyValidation();
        addEndPoint(client);
        client.listeners()
              .add(new Listener<String>() {
                  @Override
                  public
                  void connected(Connection connection) {
                      connection.send()
                                .TCP("-- TCP from client");
                      connection.send()
                                .UDP("-- UDP from client");
                  }

                  @Override
                  public
                  void received(Connection connection, String object) {
                      int incrementAndGet = ReuseTest.this.clientCount.incrementAndGet();
                      System.err.println("<C " + connection + "> " + incrementAndGet + " : " + object);
                  }
              });

        server.bind(false);
        int count = 10;
        for (int i = 1; i < count + 1; i++) {
            client.connect(5000);

            int target = i * 2;
            while (this.serverCount.get() != target || this.clientCount.get() != target) {
                System.err.println("Waiting...");
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ex) {
                }
            }

            client.closeConnections();
        }

        assertEquals(count * 2 * 2, this.clientCount.get() + this.serverCount.get());

        stopEndPoints();
        waitForThreads(10);
    }

    @Test
    public
    void localReuse() throws InitializationException, SecurityException, IOException, InterruptedException {
        this.serverCount = new AtomicInteger(0);
        this.clientCount = new AtomicInteger(0);

        Server server = new Server();
        server.disableRemoteKeyValidation();
        addEndPoint(server);
        server.listeners()
              .add(new Listener<String>() {
                  @Override
                  public
                  void connected(Connection connection) {
                      connection.send()
                                .TCP("-- LOCAL from server");
                  }

                  @Override
                  public
                  void received(Connection connection, String object) {
                      int incrementAndGet = ReuseTest.this.serverCount.incrementAndGet();
                      System.err.println("<S " + connection + "> " + incrementAndGet + " : " + object);
                  }
              });

        // ----

        Client client = new Client();
        client.disableRemoteKeyValidation();
        addEndPoint(client);
        client.listeners()
              .add(new Listener<String>() {
                  @Override
                  public
                  void connected(Connection connection) {
                      connection.send()
                                .TCP("-- LOCAL from client");
                  }

                  @Override
                  public
                  void received(Connection connection, String object) {
                      int incrementAndGet = ReuseTest.this.clientCount.incrementAndGet();
                      System.err.println("<C " + connection + "> " + incrementAndGet + " : " + object);
                  }
              });

        server.bind(false);
        int count = 10;
        for (int i = 1; i < count + 1; i++) {
            client.connect(5000);

            int target = i;
            while (this.serverCount.get() != target || this.clientCount.get() != target) {
                System.err.println("Waiting...");
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ex) {
                }
            }

            client.closeConnections();
        }

        assertEquals(count * 2, this.clientCount.get() + this.serverCount.get());

        stopEndPoints();
        waitForThreads(10);
    }
}
