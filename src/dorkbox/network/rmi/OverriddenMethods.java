package dorkbox.network.rmi;

import com.esotericsoftware.kryo.util.IdentityMap;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * Uses the "single writer principle" for fast access
 */
public
class OverriddenMethods {
    // not concurrent because they are setup during system initialization
    private volatile IdentityMap<Class<?>, Class<?>> overriddenMethods = new IdentityMap<Class<?>, Class<?>>();
    private volatile IdentityMap<Class<?>, Class<?>> overriddenReverseMethods = new IdentityMap<Class<?>, Class<?>>();

    private static final AtomicReferenceFieldUpdater<OverriddenMethods, IdentityMap> overriddenMethodsREF =
                    AtomicReferenceFieldUpdater.newUpdater(OverriddenMethods.class,
                                                           IdentityMap.class,
                                                           "overriddenMethods");

    private static final AtomicReferenceFieldUpdater<OverriddenMethods, IdentityMap> overriddenReverseMethodsREF =
                    AtomicReferenceFieldUpdater.newUpdater(OverriddenMethods.class,
                                                           IdentityMap.class,
                                                           "overriddenReverseMethods");

    private static final OverriddenMethods INSTANCE = new OverriddenMethods();
    public static synchronized OverriddenMethods INSTANCE() {
        return INSTANCE;
    }

    private
    OverriddenMethods() {
    }

    /**
     * access a snapshot of the subscriptions (single-writer-principle)
     */
    public
    Class<?> get(final Class<?> type) {
        //noinspection unchecked
        final IdentityMap<Class<?>, Class<?>> identityMap = overriddenMethodsREF.get(this);
        return identityMap.get(type);
    }

    /**
     * access a snapshot of the subscriptions (single-writer-principle)
     */
    public
    Class<?> getReverse(final Class<?> type) {
        //noinspection unchecked
        final IdentityMap<Class<?>, Class<?>> identityMap = overriddenReverseMethodsREF.get(this);
        return identityMap.get(type);
    }

    // synchronized to make sure only one writer at a time
    public synchronized
    void set(final Class<?> ifaceClass, final Class<?> implClass) {
        this.overriddenMethods.put(ifaceClass, implClass);
        this.overriddenReverseMethods.put(implClass, ifaceClass);
    }
}
