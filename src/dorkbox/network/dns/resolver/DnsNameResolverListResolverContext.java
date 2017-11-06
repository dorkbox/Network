/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package dorkbox.network.dns.resolver;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import dorkbox.network.dns.resolver.addressProvider.DnsServerAddressStream;
import dorkbox.network.dns.resolver.cache.DnsCache;
import dorkbox.network.dns.resolver.cache.DnsCacheEntry;
import io.netty.util.concurrent.Promise;

/**
 *
 */
final
class DnsNameResolverListResolverContext extends DnsNameResolverContext<List<InetAddress>> {
    DnsNameResolverListResolverContext(DnsNameResolver parent,
                                       String hostname,
                                       DnsCache resolveCache,
                                       DnsServerAddressStream nameServerAddrs) {
        super(parent, hostname, resolveCache, nameServerAddrs);
    }

    @Override
    DnsNameResolverContext<List<InetAddress>> newResolverContext(DnsNameResolver parent,
                                                                 String hostname,
                                                                 DnsCache resolveCache,
                                                                 DnsServerAddressStream nameServerAddrs) {
        return new DnsNameResolverListResolverContext(parent, hostname, resolveCache, nameServerAddrs);
    }

    @Override
    boolean finishResolve(Class<? extends InetAddress> addressType,
                          List<DnsCacheEntry> resolvedEntries,
                          Promise<List<InetAddress>> promise) {

        List<InetAddress> result = null;
        final int numEntries = resolvedEntries.size();
        for (int i = 0; i < numEntries; i++) {
            final InetAddress a = resolvedEntries.get(i).address();
            if (addressType.isInstance(a)) {
                if (result == null) {
                    result = new ArrayList<InetAddress>(numEntries);
                }
                result.add(a);
            }
        }

        if (result != null) {
            promise.trySuccess(result);
            return true;
        }
        return false;
    }
}
