// Copyright (c) 1999-2004 Brian Wellington (bwelling@xbill.org)

package dorkbox.network.dns.constants;

import dorkbox.network.dns.Mnemonic;

/**
 * Constants and functions relating to flags in the DNS header.
 *
 * In DNS query header there is a flag field in the second 16 bit word in query from bit 5 through bit 11 ([RFC1035] section 4.1.1)
 *
 * https://www.iana.org/assignments/dns-parameters/dns-parameters.xhtml#dns-parameters-12
 */

public
enum Flags {

    /**
     * query/response
     */
    QR(0, "qr"),

    /**
     * authoritative answer
     */
    AA(5, "aa"),

    /**
     * truncated
     */
    TC(6, "tc"),

    /**
     * recursion desired
     */
    RD(7, "rd"),

    /**
     * recursion available
     */
    RA(8, "ra"),

    /**
     * RESERVED
     */
    RESERVED(9, "__"),

    /**
     * authenticated data
     */
    AD(10, "ad"),

    /**
     * (security) checking disabled
     */
    CD(11, "cd"),

    /**
     * dnssec ok (extended)
     */
    DO(ExtendedFlags.DO.value(), ExtendedFlags.DO.string());


    private static Mnemonic flags = new Mnemonic("DNS Header Flag", Mnemonic.CASE_LOWER);
    static {
        flags.setMaximum(0xF);
        flags.setPrefix("FLAG");
        flags.setNumericAllowed(true);

        flags.add(QR.flagValue, "qr");
        flags.add(AA.flagValue, "aa");
        flags.add(TC.flagValue, "tc");
        flags.add(RD.flagValue, "rd");
        flags.add(RA.flagValue, "ra");
        flags.add(AD.flagValue, "ad");
        flags.add(CD.flagValue, "cd");
    }

    private final byte flagValue;
    private final String textValue;

    Flags(final int flagValue, final String textValue) {
        this.flagValue = (byte) flagValue;
        this.textValue = textValue;
    }

    public
    byte value() {
        return flagValue;
    }

    public
    String string() {
        return textValue;
    }


    public static
    Flags toFlag(final int flagBit) {
        for (Flags flag : values()) {
            if (flag.value() == flagBit) {
                return flag;
            }
        }

        throw new IllegalArgumentException("Invalid flag " + flagBit);
    }

    public static
    Flags toFlag(final String flagName) {
        for (Flags flag : values()) {
            if (flag.string().equals(flagName)) {
                return flag;
            }
        }

        throw new IllegalArgumentException("Invalid flag " + flagName);
    }


    // /**
    //  * Converts a numeric Flag into a String
    //  */
    // public static
    // String string(int i) {
    //     return flags.getText(i);
    // }
    //
    // /**
    //  * Converts a String representation of an Flag into its numeric value
    //  */
    // public static
    // int value(String s) {
    //     return flags.getValue(s);
    // }

    /**
     * Indicates if a bit in the flags field is a flag or not.  If it's part of the rcode or opcode, it's not.
     */
    public static
    boolean isFlag(int index) {
        // Checks that a numeric value is within the range
        if (index < 0 || index > 0xF || (index >= 1 && index <= 4) || (index >= 12)) {
            return false;
        }

        return true;
    }
}
