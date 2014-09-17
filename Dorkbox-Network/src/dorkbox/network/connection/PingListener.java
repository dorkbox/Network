package dorkbox.network.connection;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

// note that we specifically DO NOT implement equals/hashCode, because we cannot create two separate
// listeners that are somehow equal to each other.
public abstract class PingListener<C extends Connection> implements GenericFutureListener<Future<PingTuple<C>>> {

    public PingListener() {
    }

    @Override
    public void operationComplete(Future<PingTuple<C>> future) throws Exception {
        PingTuple<C> pingTuple = future.get();
        response(pingTuple.connection, pingTuple.responseTime);
    }

    /**
     * Called when the ping response has been received.
     */
    public abstract void response(C connection, int pingResponseTime);

    @Override
    public String toString() {
        return "PingListener";
    }
}
