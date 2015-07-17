package dorkbox.network;


import dorkbox.network.connection.Connection;
import dorkbox.network.connection.Listener;
import dorkbox.network.util.exceptions.InitializationException;
import dorkbox.network.util.exceptions.SecurityException;
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
    void reconnect() throws InitializationException, SecurityException, IOException {
        final Timer timer = new Timer();

        ConnectionOptions connectionOptions = new ConnectionOptions();
        connectionOptions.tcpPort = tcpPort;
        connectionOptions.host = host;


        final Server server = new Server(connectionOptions);
        server.disableRemoteKeyValidation();
        addEndPoint(server);
        server.bind(false);
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
        final Client client = new Client(connectionOptions);
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
                              client.reconnect();
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
