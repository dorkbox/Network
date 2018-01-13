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
import java.util.Timer;
import java.util.TimerTask;

import org.junit.Test;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.EndPointBase;
import dorkbox.network.connection.Listener;
import dorkbox.network.serialization.SerializationManager;
import dorkbox.util.exceptions.InitializationException;
import dorkbox.util.exceptions.SecurityException;

public
class ConnectionTest extends BaseTest {

    @Test
    public
    void connectLocal() throws InitializationException, SecurityException, IOException, InterruptedException {
        System.out.println("---- " + "Local");

        Configuration configuration = new Configuration();
        configuration.localChannelName = EndPointBase.LOCAL_CHANNEL;
        configuration.serialization = SerializationManager.DEFAULT();
        register(configuration.serialization);

        startServer(configuration);
        startClient(configuration);

        waitForThreads(10);
    }

    @Test
    public
    void connectTcp() throws InitializationException, SecurityException, IOException, InterruptedException {
        System.out.println("---- " + "TCP");

        Configuration configuration = new Configuration();
        configuration.tcpPort = tcpPort;
        configuration.serialization = SerializationManager.DEFAULT();
        register(configuration.serialization);

        startServer(configuration);

        configuration.host = host;
        startClient(configuration);

        waitForThreads(10);
    }

    @Test
    public
    void connectTcpUdp() throws InitializationException, SecurityException, IOException, InterruptedException {
        System.out.println("---- " + "TCP UDP");

        Configuration configuration = new Configuration();
        configuration.tcpPort = tcpPort;
        configuration.udpPort = udpPort;
        configuration.serialization = SerializationManager.DEFAULT();
        register(configuration.serialization);

        startServer(configuration);

        configuration.host = host;
        startClient(configuration);

        waitForThreads(10);
    }

    private
    Server startServer(Configuration configuration) throws InitializationException, SecurityException, IOException {
        Server server = new Server(configuration);

        addEndPoint(server);

        server.bind(false);
        server.listeners()
              .add(new Listener.OnConnected<Connection>() {
                  Timer timer = new Timer();

                  @Override
                  public
                  void connected(final Connection connection) {
                      this.timer.schedule(new TimerTask() {
                          @Override
                          public
                          void run() {
                              System.out.println("Disconnecting after 1 second.");
                              connection.close();
                          }
                      }, 1000);
                  }
              });

        server.listeners()
              .add(new Listener.OnMessageReceived<Connection, Object>() {
                  @Override
                  public void received(Connection connection, Object message) {
                      System.err.println("Received message from client: " + message.getClass().getSimpleName());
                  }
              });

        return server;
    }

    private
    Client startClient(Configuration configuration) throws InitializationException, SecurityException, IOException, InterruptedException {
        Client client;
        if (configuration != null) {
            client = new Client(configuration);
        }
        else {
            client = new Client();
        }
        addEndPoint(client);

        client.listeners()
              .add(new Listener.OnDisconnected<Connection>() {
                  @Override
                  public
                  void disconnected(Connection connection) {
                      stopEndPoints();
                  }
              });
        client.connect(5000);

        client.send()
              .TCP(new BMessage())
              .flush();

        return client;
    }

    private
    void register(dorkbox.util.SerializationManager manager) {
        manager.register(BMessage.class);
    }

    public static
    class BMessage {
        public
        BMessage() {
        }
    }
}
