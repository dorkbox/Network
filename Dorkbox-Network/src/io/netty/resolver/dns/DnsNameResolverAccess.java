package io.netty.resolver.dns;

import io.netty.buffer.ByteBuf;

public final
class DnsNameResolverAccess {

    private
    DnsNameResolverAccess() {
    }

    public static
    String decodeDomainName(final ByteBuf byteBuff) {
        return DnsNameResolverContext.decodeDomainName(byteBuff);
    }
}
