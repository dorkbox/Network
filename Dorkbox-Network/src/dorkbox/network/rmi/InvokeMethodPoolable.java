package dorkbox.network.rmi;

import dorkbox.util.objectPool.PoolableObject;

public class InvokeMethodPoolable implements PoolableObject<InvokeMethod> {
    @Override
    public InvokeMethod create() {
        return new InvokeMethod();
    }
}