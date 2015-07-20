package dorkbox.network.rmi;


/** Internal message to invoke methods remotely. */
public
class InvokeMethod implements RmiMessages {
    public int objectID;
    public CachedMethod cachedMethod;
    public Object[] args;

    // The top bits of the ID indicate if the remote invocation should respond with return values and exceptions, respectively.
    // The remaining bites are a counter. This means up to 63 responses can be stored before undefined behavior occurs due to
    // possible duplicate IDs. A response data of 0 means to not respond.
    public byte responseData;


    public InvokeMethod() {
    }
}
