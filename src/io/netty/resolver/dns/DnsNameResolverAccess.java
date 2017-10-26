/*
 * Copyright 2010 dorkbox, llc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.netty.resolver.dns;

import io.netty.buffer.ByteBuf;
import io.netty.resolver.ResolvedAddressTypes;

public final
class DnsNameResolverAccess {

    public static
    String decodeDomainName(final ByteBuf byteBuff) {
        return DnsNameResolverContext.decodeDomainName(byteBuff);
    }

    public static
    ResolvedAddressTypes getDefaultResolvedAddressTypes() {
        return DnsNameResolver.DEFAULT_RESOLVE_ADDRESS_TYPES;
    }

    private
    DnsNameResolverAccess() {
    }
}
