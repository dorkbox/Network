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


        Server server = null;

        // try to reconnect 10 times
        boolean success = false;
        for (int i = 0; i < 10; i++) {
            try {
                server = new Server(configuration);
                server.disableRemoteKeyValidation();

                server.bind(false);
                addEndPoint(server);
                success = true;
                break;
            } catch (Throwable ignored) {
                System.out.println("Retrying...");
                Thread.sleep(4000);
            }
        }

        if (!success) {
            throw new RuntimeException("Unable to bind to TCP port. Aborting.");
        }

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
