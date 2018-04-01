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

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.EndPoint;
import dorkbox.network.connection.Listener;
import dorkbox.network.connection.Listener.OnConnected;
import dorkbox.network.connection.Listener.OnDisconnected;
import dorkbox.network.serialization.Serialization;
import dorkbox.util.exceptions.SecurityException;
import dorkbox.util.serialization.SerializationManager;

public
class ConnectionTest extends BaseTest {
    private AtomicInteger successCount;

    @Test
    public
    void connectLocal() throws SecurityException, IOException {
        System.out.println("---- " + "Local");
        successCount = new AtomicInteger(0);

        Configuration configuration = new Configuration();
        configuration.localChannelName = EndPoint.LOCAL_CHANNEL;
        configuration.serialization = Serialization.DEFAULT();
        register(configuration.serialization);

        startServer(configuration);
        startClient(configuration);

        waitForThreads(10);
        Assert.assertEquals(6, successCount.get());
    }

    @Test
    public
    void connectTcp() throws SecurityException, IOException {
        System.out.println("---- " + "TCP");
        successCount = new AtomicInteger(0);

        Configuration configuration = new Configuration();
        configuration.tcpPort = tcpPort;
        configuration.serialization = Serialization.DEFAULT();
        register(configuration.serialization);

        startServer(configuration);

        configuration.host = host;
        startClient(configuration);

        waitForThreads(10);
        Assert.assertEquals(6, successCount.get());
    }

    @Test
    public
    void connectUdp() throws SecurityException, IOException {
        System.out.println("---- " + "UDP");
        successCount = new AtomicInteger(0);

        Configuration configuration = new Configuration();
        configuration.udpPort = udpPort;
        configuration.serialization = Serialization.DEFAULT();
        register(configuration.serialization);

        startServer(configuration);

        configuration.host = host;
        startClient(configuration);

        waitForThreads(10);
        Assert.assertEquals(6, successCount.get());
    }

    @Test
    public
    void connectTcpUdp() throws SecurityException, IOException {
        System.out.println("---- " + "TCP UDP");
        successCount = new AtomicInteger(0);

        Configuration configuration = new Configuration();
        configuration.tcpPort = tcpPort;
        configuration.udpPort = udpPort;
        configuration.serialization = Serialization.DEFAULT();
        register(configuration.serialization);

        startServer(configuration);

        configuration.host = host;
        startClient(configuration);

        waitForThreads(10);
        Assert.assertEquals(6, successCount.get());
    }

    private
    Server startServer(final Configuration configuration) throws SecurityException {
        final Server server = new Server(configuration);

        addEndPoint(server);

        server.bind(false);
        server.listeners()
              .add(new OnConnected<Connection>() {
                  @Override
                  public
                  void connected(final Connection connection) {
                      successCount.getAndIncrement();
                  }
              })
              .add(new OnDisconnected<Connection>() {
                  @Override
                  public
                  void disconnected(Connection connection) {
                      successCount.getAndIncrement();
                  }
              })
              .add(new Listener.OnMessageReceived<Connection, Object>() {
                  @Override
                  public void received(Connection connection, Object message) {
                      System.err.println("Received message from client: " + message.getClass().getSimpleName());

                      successCount.getAndIncrement();
                      if (configuration.tcpPort > 0) {
                          connection.send()
                                    .TCP(message);
                      }
                      else {
                          connection.send()
                                    .UDP(message);
                      }
                  }
              });

        return server;
    }

    private
    Client startClient(final Configuration configuration) throws SecurityException, IOException {
        Client client;
        if (configuration != null) {
            client = new Client(configuration);
        }
        else {
            client = new Client();
        }
        addEndPoint(client);

        client.listeners()
              .add(new OnConnected<Connection>() {
                  @Override
                  public
                  void connected(final Connection connection) {
                      successCount.getAndIncrement();
                  }
              })
              .add(new OnDisconnected<Connection>() {
                  @Override
                  public
                  void disconnected(Connection connection) {
                      successCount.getAndIncrement();
                  }
              })
              .add(new Listener.OnMessageReceived<Connection, Object>() {
                  @Override
                  public
                  void received(Connection connection, Object message) {
                      System.err.println("Received message from server: " + message.getClass()
                                                                                   .getSimpleName());
                      System.err.println("Now disconnecting!");
                      successCount.getAndIncrement();

                      stopEndPoints();
                  }
              });
        client.connect(5000);

        if (configuration.tcpPort > 0) {
            client.send()
                  .TCP(new BMessage());
        }
        else {
            client.send()
                  .UDP(new BMessage());
        }

        return client;
    }

    private
    void register(SerializationManager manager) {
        manager.register(BMessage.class);
    }

    public static
    class BMessage {
        public
        BMessage() {
        }
    }
}
