package dorkbox.network.util;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public abstract class ConcurrentHashMapFactory<K, V> extends ConcurrentHashMap<K, V> implements ConcurrentMap<K, V> {

    private static final long serialVersionUID = -1796263935845885270L;

    public ConcurrentHashMapFactory() {
    }

    public abstract V createNewOject(Object... args);


    public final V getOrCreate(K key, Object... args) {
        V orig = get(key);

        if (orig == null) {
            // It's OK to construct a new object that ends up not being used
            orig = createNewOject(args);
            V putByOtherThreadJustNow = putIfAbsent(key, orig);
            if (putByOtherThreadJustNow != null) {
                // Some other thread "won"
                orig = putByOtherThreadJustNow;
            } else {
                // This thread was the winner
            }
        }
        return orig;
    }
}
