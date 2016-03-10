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

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.Listener;
import dorkbox.util.exceptions.InitializationException;
import dorkbox.util.exceptions.SecurityException;
import org.junit.Test;

import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

public
class ReconnectTest extends BaseTest {

    @Test
    public
    void reconnect() throws InitializationException, SecurityException, IOException, InterruptedException {
        final Timer timer = new Timer();

        Configuration configuration = new Configuration();
        configuration.tcpPort = tcpPort;
        configuration.host = host;


        Server server = new Server(configuration);
        server.disableRemoteKeyValidation();

        server.bind(false);
        addEndPoint(server);

        server.listeners()
              .add(new Listener<Object>() {
                  @Override
                  public
                  void connected(final Connection connection) {
                      timer.schedule(new TimerTask() {
                          @Override
                          public
                          void run() {
                              System.out.println("Disconnecting after 2 seconds.");
                              connection.close();
                          }
                      }, 2000);
                  }
              });

        // ----

        final AtomicInteger reconnectCount = new AtomicInteger();
        final Client client = new Client(configuration);
        client.disableRemoteKeyValidation();
        addEndPoint(client);
        client.listeners()
              .add(new Listener<Object>() {
                  @Override
                  public
                  void disconnected(Connection connection) {
                      if (reconnectCount.getAndIncrement() == 2) {
                          stopEndPoints();
                          return;
                      }
                      new Thread() {
                          @Override
                          public
                          void run() {
                              System.out.println("Reconnecting: " + reconnectCount.get());
                              try {
                                  client.reconnect();
                              } catch (IOException e) {
                                  e.printStackTrace();
                              }
                          }
                      }.start();
                  }
              });
        client.connect(5000);

        waitForThreads(10);
        System.err.println("Connection count (after reconnecting) is: " + reconnectCount.get());
        assertEquals(3, reconnectCount.get());
    }
}
