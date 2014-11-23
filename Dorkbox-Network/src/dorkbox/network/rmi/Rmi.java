package dorkbox.network.rmi;



public interface Rmi {
    /**
     * Registers an object to allow the remote end of the RmiBridge connections to access it using the specified ID.
     *
     * @param objectID Must not be Integer.MAX_VALUE.
     */
    public void register(int objectID, Object object);

    /**
     * Removes an object. The remote end of the RmiBridge connection will no longer be able to access it.
     */
    public void remove(int objectID);

    /**
     * Removes an object. The remote end of the RmiBridge connection will no longer be able to access it.
     */
    public void remove(Object object);
}
