package dorkbox.network.connection;




public abstract class Listener<M extends Object> extends ListenerRaw<Connection, M> {
    public Listener() {
        super(0);
    }
}
