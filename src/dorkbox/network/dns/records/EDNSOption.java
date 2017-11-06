// Copyright (c) 1999-2004 Brian Wellington (bwelling@xbill.org)
package dorkbox.network.dns.records;

import java.io.IOException;
import java.util.Arrays;

import dorkbox.network.dns.DnsInput;
import dorkbox.network.dns.DnsOutput;
import dorkbox.network.dns.Mnemonic;
import dorkbox.network.dns.exceptions.WireParseException;

/**
 * DNS extension options, as described in RFC 2671.  The rdata of an OPT record
 * is defined as a list of options; this represents a single option.
 *
 * @author Brian Wellington
 * @author Ming Zhou &lt;mizhou@bnivideo.com&gt;, Beaumaris Networks
 */
public abstract
class EDNSOption {

    private final int code;


    public static
    class Code {
        /**
         * Name Server Identifier, RFC 5001
         */
        public final static int NSID = 3;
        /**
         * Client Subnet, defined in draft-vandergaast-edns-client-subnet-02
         */
        public final static int CLIENT_SUBNET = 8;
        private static Mnemonic codes = new Mnemonic("EDNS Option Codes", Mnemonic.CASE_UPPER);

        private
        Code() {}

        static {
            codes.setMaximum(0xFFFF);
            codes.setPrefix("CODE");
            codes.setNumericAllowed(true);

            codes.add(NSID, "NSID");
            codes.add(CLIENT_SUBNET, "CLIENT_SUBNET");
        }

        /**
         * Converts an EDNS Option Code into its textual representation
         */
        public static
        String string(int code) {
            return codes.getText(code);
        }

        /**
         * Converts a textual representation of an EDNS Option Code into its
         * numeric value.
         *
         * @param s The textual representation of the option code
         *
         * @return The option code, or -1 on error.
         */
        public static
        int value(String s) {
            return codes.getValue(s);
        }
    }

    /**
     * Creates an option with the given option code and data.
     */
    public
    EDNSOption(int code) {
        this.code = DnsRecord.checkU16("code", code);
    }

    /**
     * Returns the EDNS Option's code.
     *
     * @return the option code
     */
    public
    int getCode() {
        return code;
    }

    /**
     * Converts the wire format of an EDNS Option (including code and length) into
     * the type-specific format.
     *
     * @return The option, in wire format.
     */
    public static
    EDNSOption fromWire(byte[] b) throws IOException {
        return fromWire(new DnsInput(b));
    }

    /**
     * Converts the wire format of an EDNS Option (including code and length) into
     * the type-specific format.
     *
     * @param in The input stream.
     */
    static
    EDNSOption fromWire(DnsInput in) throws IOException {
        int code, length;

        code = in.readU16();
        length = in.readU16();
        if (in.remaining() < length) {
            throw new WireParseException("truncated option");
        }
        in.setActive(length);
        EDNSOption option;
        switch (code) {
            case Code.NSID:
                option = new NSIDOption();
                break;
            case Code.CLIENT_SUBNET:
                option = new ClientSubnetOption();
                break;
            default:
                option = new GenericEDNSOption(code);
                break;
        }
        option.optionFromWire(in);
        in.restoreActive();

        return option;
    }

    /**
     * Converts the wire format of an EDNS Option (the option data only) into the
     * type-specific format.
     *
     * @param in The input Stream.
     */
    abstract
    void optionFromWire(DnsInput in) throws IOException;

    /**
     * Converts an EDNS Option (including code and length) into wire format.
     *
     * @return The option, in wire format.
     */
    public
    byte[] toWire() throws IOException {
        DnsOutput out = new DnsOutput();
        toWire(out);
        return out.toByteArray();
    }

    /**
     * Converts an EDNS Option (including code and length) into wire format.
     *
     * @param out The output stream.
     */
    void toWire(DnsOutput out) {
        out.writeU16(code);
        int lengthPosition = out.current();
        out.writeU16(0); /* until we know better */
        optionToWire(out);
        int length = out.current() - lengthPosition - 2;
        out.writeU16At(length, lengthPosition);
    }

    /**
     * Converts an EDNS Option (the type-specific option data only) into wire format.
     *
     * @param out The output stream.
     */
    abstract
    void optionToWire(DnsOutput out);

    /**
     * Generates a hash code based on the EDNS Option's data.
     */
    public
    int hashCode() {
        byte[] array = getData();
        int hashval = 0;
        for (int i = 0; i < array.length; i++) {
            hashval += ((hashval << 3) + (array[i] & 0xFF));
        }
        return hashval;
    }

    /**
     * Determines if two EDNS Options are identical.
     *
     * @param arg The option to compare to
     *
     * @return true if the options are equal, false otherwise.
     */
    public
    boolean equals(Object arg) {
        if (arg == null || !(arg instanceof EDNSOption)) {
            return false;
        }
        EDNSOption opt = (EDNSOption) arg;
        if (code != opt.code) {
            return false;
        }
        return Arrays.equals(getData(), opt.getData());
    }

    public
    String toString() {
        StringBuilder sb = new StringBuilder();

        sb.append("{");
        sb.append(EDNSOption.Code.string(code));
        sb.append(": ");
        sb.append(optionToString());
        sb.append("}");

        return sb.toString();
    }

    abstract
    String optionToString();

    /**
     * Returns the EDNS Option's data, as a byte array.
     *
     * @return the option data
     */
    byte[] getData() {
        DnsOutput out = new DnsOutput();
        optionToWire(out);
        return out.toByteArray();
    }

}
