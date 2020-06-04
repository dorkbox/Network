/*
 * Copyright 2016 dorkbox, llc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkbox.network;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.Listener;
import dorkbox.util.exceptions.SecurityException;

public
class DisconnectReconnectTest extends BaseTest {
    private final Timer timer = new Timer();

    @Test
    public
    void reconnect() throws SecurityException, IOException {

        Configuration configuration = new Configuration();
        configuration.tcpPort = tcpPort;
        configuration.host = host;


        Server server = new Server(configuration);

        server.bind(false);
        addEndPoint(server);

        server.listeners()
              .add(new Listener.OnConnected<Connection>() {
                  @Override
                  public
                  void connected(final Connection connection) {
                      System.out.println("Disconnecting after 2 seconds.");
                      timer.schedule(new TimerTask() {
                          @Override
                          public
                          void run() {
                              System.out.println("Disconnecting....");
                              connection.close();
                          }
                      }, 2000);
                  }
              });

        // ----

        final AtomicInteger reconnectCount = new AtomicInteger();
        final Client client = new Client(configuration);
        addEndPoint(client);
        client.listeners()
              .add(new Listener.OnDisconnected<Connection>() {
                  @Override
                  public
                  void disconnected(Connection connection) {
                      int count = reconnectCount.getAndIncrement();
                      if (count == 3) {
                          System.out.println("Shutting down");
                          stopEndPoints();
                      }
                      else {
                          System.out.println("Reconnecting: " + count);
                          try {
                              client.reconnect();
                          } catch (IOException e) {
                              e.printStackTrace();
                          }
                      }
                  }
              });
        client.connect(5000);

        waitForThreads();

        System.err.println("Connection count (after reconnecting) is: " + reconnectCount.get());
        assertEquals(4, reconnectCount.get());
    }
}
