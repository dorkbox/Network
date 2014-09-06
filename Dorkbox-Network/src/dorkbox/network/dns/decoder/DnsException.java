package dorkbox.network.dns.decoder;

import io.netty.handler.codec.dns.DnsResponseCode;

/**
 * Exception which is used to notify the promise if the DNS query fails.
 */
public final class DnsException extends Exception {
    private static final long serialVersionUID = 1310161373613598975L;

    private DnsResponseCode errorCode;

    public DnsException(DnsResponseCode errorCode) {
        if (errorCode == null) {
            throw new NullPointerException("errorCode");
        }
        this.errorCode = errorCode;
    }

    public DnsResponseCode errorCode() {
        return this.errorCode;
    }
}