package dorkbox.network;



import dorkbox.network.connection.Connection;
import dorkbox.network.connection.EndPoint;
import dorkbox.network.connection.Listener;
import dorkbox.network.util.ConnectionSerializationManager;
import dorkbox.network.util.KryoConnectionSerializationManager;
import dorkbox.network.util.exceptions.InitializationException;
import dorkbox.network.util.exceptions.SecurityException;
import org.junit.Test;

import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;

public
class ConnectionTest extends BaseTest {

    @Test
    public
    void connectLocal() throws InitializationException, SecurityException, IOException {
        System.out.println("---- " + "Local");

        ConnectionOptions connectionOptions = new ConnectionOptions(EndPoint.LOCAL_CHANNEL);
        connectionOptions.serializationManager = KryoConnectionSerializationManager.DEFAULT();
        register(connectionOptions.serializationManager);

        startServer(connectionOptions);
        startClient(connectionOptions);

        waitForThreads(10);
    }

    @Test
    public
    void connectTcp() throws InitializationException, SecurityException, IOException {
        System.out.println("---- " + "TCP");

        ConnectionOptions connectionOptions = new ConnectionOptions();
        connectionOptions.tcpPort = tcpPort;
        connectionOptions.serializationManager = KryoConnectionSerializationManager.DEFAULT();
        register(connectionOptions.serializationManager);

        startServer(connectionOptions);

        connectionOptions.host = host;
        startClient(connectionOptions);

        waitForThreads(10);
    }

    @Test
    public
    void connectTcpUdp() throws InitializationException, SecurityException, IOException {
        System.out.println("---- " + "TCP UDP");

        ConnectionOptions connectionOptions = new ConnectionOptions();
        connectionOptions.tcpPort = tcpPort;
        connectionOptions.udpPort = udpPort;
        connectionOptions.serializationManager = KryoConnectionSerializationManager.DEFAULT();
        register(connectionOptions.serializationManager);

        startServer(connectionOptions);

        connectionOptions.host = host;
        startClient(connectionOptions);

        waitForThreads(10);
    }

    @Test
    public
    void connectTcpUdt() throws InitializationException, SecurityException, IOException {
        System.out.println("---- " + "TCP UDT");

        ConnectionOptions connectionOptions = new ConnectionOptions();
        connectionOptions.tcpPort = tcpPort;
        connectionOptions.udtPort = udtPort;
        connectionOptions.serializationManager = KryoConnectionSerializationManager.DEFAULT();
        register(connectionOptions.serializationManager);

        startServer(connectionOptions);

        connectionOptions.host = host;
        startClient(connectionOptions);

        waitForThreads(10);
    }

    @Test
    public
    void connectTcpUdpUdt() throws InitializationException, SecurityException, IOException {
        System.out.println("---- " + "TCP UDP UDT");

        ConnectionOptions connectionOptions = new ConnectionOptions();
        connectionOptions.tcpPort = tcpPort;
        connectionOptions.udpPort = udpPort;
        connectionOptions.udtPort = udtPort;
        connectionOptions.serializationManager = KryoConnectionSerializationManager.DEFAULT();
        register(connectionOptions.serializationManager);

        startServer(connectionOptions);

        connectionOptions.host = host;

        startClient(connectionOptions);

        waitForThreads(10);
    }

    private
    Server startServer(ConnectionOptions connectionOptions) throws InitializationException, SecurityException, IOException {
        Server server = new Server(connectionOptions);

        server.disableRemoteKeyValidation();
        addEndPoint(server);

        server.bind(false);
        server.listeners()
              .add(new Listener<Object>() {
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

                  @Override
                  public void received(Connection connection, Object message) {
                      System.err.println("Received message from client: " + message.getClass().getSimpleName());
                  }
              });

        return server;
    }

    private
    Client startClient(ConnectionOptions connectionOptions) throws InitializationException, SecurityException, IOException {
        Client client;
        if (connectionOptions != null) {
            client = new Client(connectionOptions);
        }
        else {
            client = new Client();
        }
        client.disableRemoteKeyValidation();
        addEndPoint(client);

        client.listeners()
              .add(new Listener<Object>() {
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
    void register(ConnectionSerializationManager kryoMT) {
        kryoMT.register(BMessage.class);
    }

    public static
    class BMessage {
        public
        BMessage() {
        }
    }
}
