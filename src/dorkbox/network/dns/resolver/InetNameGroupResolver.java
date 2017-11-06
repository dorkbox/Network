package dorkbox.network.dns.resolver;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

import io.netty.resolver.AddressResolver;
import io.netty.resolver.SimpleNameResolver;
import io.netty.util.concurrent.EventExecutor;

public abstract class InetNameGroupResolver extends SimpleNameResolver<List<InetAddress>> {
    private volatile AddressResolver<InetSocketAddress> addressResolver;

    /**
     * @param executor the {@link EventExecutor} which is used to notify the listeners of the {@link Future} returned
     *                 by {@link #resolve(String)}
     */
    protected
    InetNameGroupResolver(EventExecutor executor) {
        super(executor);
    }

    /**
     * Return a {@link AddressResolver} that will use this name resolver underneath.
     * It's cached internally, so the same instance is always returned.
     */
    public AddressResolver<InetSocketAddress> asAddressResolver() {
        AddressResolver<InetSocketAddress> result = addressResolver;
        if (result == null) {
            synchronized (this) {
                result = addressResolver;
                if (result == null) {
                    addressResolver = result = new InetSocketAddressGroupResolver(executor(), this);
                }
            }
        }
        return result;
    }
}
