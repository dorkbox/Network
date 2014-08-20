package dorkbox.network.connection;



import java.util.Collection;

public interface ISessionManager {
    /** Called when a message is received*/
    public void notifyOnMessage(Connection connection, Object message);

    /** Called when the connection has been idle (read & write) for 2 seconds */
    public void notifyOnIdle(Connection connection);


    public void connectionConnected(Connection connection);
    public void connectionDisconnected(Connection connection);

    /** Called when there is an error of some kind during the up/down stream process */
    public void connectionError(Connection connection, Throwable throwable);

    /** Returns a non-modifiable list of active connections */
    public Collection<Connection> getConnections();
}
