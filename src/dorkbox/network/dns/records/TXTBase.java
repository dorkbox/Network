// Copyright (c) 1999-2004 Brian Wellington (bwelling@xbill.org)

package dorkbox.network.dns.records;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import dorkbox.network.dns.Compression;
import dorkbox.network.dns.DnsInput;
import dorkbox.network.dns.DnsOutput;
import dorkbox.network.dns.Name;
import dorkbox.network.dns.exceptions.TextParseException;
import dorkbox.network.dns.utils.Tokenizer;

/**
 * Implements common functionality for the many record types whose format
 * is a list of strings.
 *
 * @author Brian Wellington
 */

abstract
class TXTBase extends DnsRecord {

    private static final long serialVersionUID = -4319510507246305931L;

    protected List strings;

    protected
    TXTBase() {}

    protected
    TXTBase(Name name, int type, int dclass, long ttl) {
        super(name, type, dclass, ttl);
    }

    protected
    TXTBase(Name name, int type, int dclass, long ttl, String string) {
        this(name, type, dclass, ttl, Collections.singletonList(string));
    }

    protected
    TXTBase(Name name, int type, int dclass, long ttl, List strings) {
        super(name, type, dclass, ttl);
        if (strings == null) {
            throw new IllegalArgumentException("strings must not be null");
        }
        this.strings = new ArrayList(strings.size());
        Iterator it = strings.iterator();
        try {
            while (it.hasNext()) {
                String s = (String) it.next();
                this.strings.add(byteArrayFromString(s));
            }
        } catch (TextParseException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }

    @Override
    void rrFromWire(DnsInput in) throws IOException {
        strings = new ArrayList(2);
        while (in.remaining() > 0) {
            byte[] b = in.readCountedString();
            strings.add(b);
        }
    }

    @Override
    void rrToWire(DnsOutput out, Compression c, boolean canonical) {
        Iterator it = strings.iterator();
        while (it.hasNext()) {
            byte[] b = (byte[]) it.next();
            out.writeCountedString(b);
        }
    }

    /**
     * converts to a String
     */
    @Override
    void rrToString(StringBuilder sb) {
        Iterator it = strings.iterator();
        while (it.hasNext()) {
            byte[] array = (byte[]) it.next();
            sb.append(byteArrayToString(array, true));
            if (it.hasNext()) {
                sb.append(" ");
            }
        }
    }

    @Override
    void rdataFromString(Tokenizer st, Name origin) throws IOException {
        strings = new ArrayList(2);
        while (true) {
            Tokenizer.Token t = st.get();
            if (!t.isString()) {
                break;
            }
            try {
                strings.add(byteArrayFromString(t.value));
            } catch (TextParseException e) {
                throw st.exception(e.getMessage());
            }

        }
        st.unget();
    }

    /**
     * Returns the text strings
     *
     * @return A list of Strings corresponding to the text strings.
     */
    public
    List getStrings() {
        List list = new ArrayList(strings.size());
        for (int i = 0; i < strings.size(); i++) {
            list.add(byteArrayToString((byte[]) strings.get(i), false));
        }
        return list;
    }

    /**
     * Returns the text strings
     *
     * @return A list of byte arrays corresponding to the text strings.
     */
    public
    List getStringsAsByteArrays() {
        return strings;
    }

}
