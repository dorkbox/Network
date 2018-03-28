// Copyright (c) 1999-2004 Brian Wellington (bwelling@xbill.org)

package dorkbox.network.dns.records;

import java.io.IOException;

import dorkbox.network.dns.DnsInput;
import dorkbox.network.dns.DnsOutput;
import dorkbox.network.dns.constants.DnsOpCode;
import dorkbox.network.dns.constants.DnsResponseCode;
import dorkbox.network.dns.constants.DnsSection;
import dorkbox.network.dns.constants.Flags;
import dorkbox.util.FastThreadLocal;
import dorkbox.util.MersenneTwisterFast;
import dorkbox.util.OS;

/**
 * A DNS message header
 *
 * @author Brian Wellington
 * @see DnsMessage
 */

public
class Header implements Cloneable {

    private int id;
    private int flags;
    private int[] counts;

    private static final
    FastThreadLocal<MersenneTwisterFast> random = new FastThreadLocal<MersenneTwisterFast>() {
        @Override
        public
        MersenneTwisterFast initialValue() {
            return new MersenneTwisterFast();
        }
    };


    /**
     * The length of a DNS Header in wire format.
     */
    public static final int LENGTH = 12;

    /**
     * Create a new empty header with a random message id
     */
    public
    Header() {
        init();
    }

    private
    void init() {
        counts = new int[4];
        flags = 0;
        id = -1;
    }

    /**
     * Creates a new Header from its DNS wire format representation
     *
     * @param b A byte array containing the DNS Header.
     */
    public
    Header(byte[] b) throws IOException {
        this(new DnsInput(b));
    }

    /**
     * Parses a Header from a stream containing DNS wire format.
     */
    Header(DnsInput in) throws IOException {
        this(in.readU16());
        flags = in.readU16();
        for (int i = 0; i < counts.length; i++) {
            counts[i] = in.readU16();
        }
    }

    /**
     * Create a new empty header.
     *
     * @param id The message id
     */
    public
    Header(int id) {
        init();
        setID(id);
    }

    public
    byte[] toWire() {
        DnsOutput out = new DnsOutput();
        toWire(out);
        return out.toByteArray();
    }

    void toWire(DnsOutput out) {
        out.writeU16(getID());
        out.writeU16(flags);
        for (int i = 0; i < counts.length; i++) {
            out.writeU16(counts[i]);
        }
    }

    /**
     * Retrieves the message ID
     */
    public
    int getID() {
        if (id >= 0) {
            return id;
        }
        synchronized (this) {
            if (id < 0) {
                id = random.get().nextInt(0xffff);
            }
            return id;
        }
    }

    /**
     * Sets the message ID
     */
    public
    void setID(int id) {
        if (id < 0 || id > 0xffff) {
            throw new IllegalArgumentException("DNS message ID " + id + " is out of range");
        }
        this.id = id;
    }

    /**
     * Sets a flag to the supplied value
     *
     * @see Flags
     */
    public
    void setFlag(Flags flag) {
        checkFlag(flag);
        flags = setFlag(flags, flag, true);
    }

    static private
    void checkFlag(int flag) {
        if (!Flags.isFlag(flag)) {
            throw new IllegalArgumentException("invalid flag bit " + flag);
        }
    }

    static private
    void checkFlag(Flags flag) {
        if (!validFlag(flag)) {
            throw new IllegalArgumentException("invalid flag bit " + flag);
        }
    }

    static private
    boolean validFlag(Flags flag) {
        return flag != null && (flag.value() >= 0 && flag.value() <= 0xF && Flags.isFlag(flag.value()));
    }

    static
    int setFlag(int flags, Flags flag, boolean value) {
        checkFlag(flag);

        // bits are indexed from left to right
        if (value) {
            return flags |= (1 << (15 - flag.value()));
        }
        else {
            return flags &= ~(1 << (15 - flag.value()));
        }
    }

    /**
     * Sets a flag to the supplied value
     *
     * @see Flags
     */
    public
    void unsetFlag(Flags flag) {
        checkFlag(flag);
        flags = setFlag(flags, flag, false);
    }

    boolean[] getFlags() {
        boolean[] array = new boolean[16];
        for (int i = 0; i < array.length; i++) {
            if (Flags.isFlag(i)) {
                array[i] = getFlag(i);
            }
        }
        return array;
    }

    /**
     * Retrieves a flag
     *
     * @see Flags
     */
    public
    boolean getFlag(Flags flag) {
        // bit s are indexed from left to right
        return (flags & (1 << (15 - flag.value()))) != 0;
    }

    /**
     * Retrieves a flag.
     *
     * @param flagValue ALWAYS checked before using, so additional checks are not necessary
     * @see Flags
     */
    private
    boolean getFlag(int flagValue) {
        // bits are indexed from left to right
        return (flags & (1 << (15 - flagValue))) != 0;
    }

    void setCount(int field, int value) {
        if (value < 0 || value > 0xFFFF) {
            throw new IllegalArgumentException("DNS section count " + value + " is out of range");
        }
        counts[field] = value;
    }

    void incCount(int field) {
        if (counts[field] == 0xFFFF) {
            throw new IllegalStateException("DNS section count cannot " + "be incremented");
        }
        counts[field]++;
    }

    void decCount(int field) {
        if (counts[field] == 0) {
            throw new IllegalStateException("DNS section count cannot " + "be decremented");
        }
        counts[field]--;
    }

    int getFlagsByte() {
        return flags;
    }

    /* Creates a new Header identical to the current one */
    @Override
    public
    Object clone() {
        Header h = new Header();
        h.id = id;
        h.flags = flags;
        System.arraycopy(counts, 0, h.counts, 0, counts.length);
        return h;
    }

    /**
     * Converts the header into a String
     */
    @Override
    public
    String toString() {
        return toStringWithRcode(getRcode());
    }

    /**
     * Retrieves the message's rcode
     *
     * @see DnsResponseCode
     */
    public
    int getRcode() {
        return flags & 0xF;
    }

    /**
     * Sets the message's rcode
     *
     * @see DnsResponseCode
     */
    public
    void setRcode(int value) {
        if (value < 0 || value > 0xF) {
            throw new IllegalArgumentException("DNS DnsResponseCode " + value + " is out of range");
        }
        flags &= ~0xF;
        flags |= value;
    }

    String toStringWithRcode(int newrcode) {
        StringBuilder sb = new StringBuilder();

        sb.append(";; ->>HEADER<<- ");
        sb.append("opcode: " + DnsOpCode.string(getOpcode()));
        sb.append(", status: " + DnsResponseCode.string(newrcode));
        sb.append(", id: " + getID());
        sb.append(OS.LINE_SEPARATOR);

        sb.append(";; flags: ")
          .append(printFlags());
        sb.append("; ");
        for (int i = 0; i < 4; i++) {
            sb.append(DnsSection.string(i))
              .append(": ")
              .append(getCount(i))
              .append(" ");
        }
        return sb.toString();
    }

    /**
     * Retrieves the mesasge's opcode
     *
     * @see DnsOpCode
     */
    public
    int getOpcode() {
        return (flags >> 11) & 0xF;
    }

    /**
     * Sets the message's opcode
     *
     * @see DnsOpCode
     */
    public
    void setOpcode(int value) {
        if (value < 0 || value > 0xF) {
            throw new IllegalArgumentException("DNS DnsOpCode " + value + "is out of range");
        }
        flags &= 0x87FF;
        flags |= (value << 11);
    }

    /**
     * Retrieves the record count for the given section
     *
     * @see DnsSection
     */
    public
    int getCount(int field) {
        return counts[field];
    }

    /**
     * Converts the header's flags into a String
     */
    String printFlags() {
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < 16; i++) {
            if (Flags.isFlag(i) && getFlag(i)) {
                Flags flag = Flags.toFlag(i);
                sb.append(flag.string());
                sb.append(" ");
            }
        }
        return sb.toString();
    }
}
