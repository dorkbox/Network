// Copyright (c) 1999-2004 Brian Wellington (bwelling@xbill.org)
package dorkbox.network.dns.records;

import java.io.IOException;

import dorkbox.network.dns.DnsInput;
import dorkbox.network.dns.DnsOutput;
import dorkbox.network.dns.utils.base16;

/**
 * An EDNSOption with no internal structure.
 *
 * @author Ming Zhou &lt;mizhou@bnivideo.com&gt;, Beaumaris Networks
 * @author Brian Wellington
 */
public
class GenericEDNSOption extends EDNSOption {

    private byte[] data;

    GenericEDNSOption(int code) {
        super(code);
    }

    /**
     * Construct a generic EDNS option.
     *
     * @param data The contents of the option.
     */
    public
    GenericEDNSOption(int code, byte[] data) {
        super(code);
        this.data = DnsRecord.checkByteArrayLength("option data", data, 0xFFFF);
    }

    @Override
    void optionFromWire(DnsInput in) throws IOException {
        data = in.readByteArray();
    }

    @Override
    void optionToWire(DnsOutput out) {
        out.writeByteArray(data);
    }

    @Override
    String optionToString() {
        return "<" + base16.toString(data) + ">";
    }

}
