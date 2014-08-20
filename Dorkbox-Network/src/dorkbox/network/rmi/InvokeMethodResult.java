package dorkbox.network.rmi;

/**
 * Internal message to return the result of a remotely invoked method.
 */
public class InvokeMethodResult implements RmiMessages {
    public int    objectID;
    public byte   responseID;
    public Object result;
}