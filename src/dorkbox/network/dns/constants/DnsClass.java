// Copyright (c) 1999-2004 Brian Wellington (bwelling@xbill.org)

package dorkbox.network.dns.constants;

import dorkbox.network.dns.Mnemonic;
import dorkbox.network.dns.exceptions.InvalidDClassException;

/**
 * Constants and functions relating to DNS classes.  This is called DnsClass to avoid confusion with Class.
 *
 * @author Brian Wellington
 */
public final
class DnsClass {

    /**
     * Internet DNS resource record class: {@code IN}
     */
    public static final int IN = 1;

    /**
     * Computer Science Network network DNS resource record class: {@code CSNET}. It was never installed as a top-level domain
     * in the Domain Name System, but parsed in the message routing logic of mail transport agents (MTA). It was introduced in 1985.
     */
    public static final int CS = 2;

    /**
     * Computer Science Network network DNS resource record class: {@code CSNET}. It was never installed as a top-level domain
     * in the Domain Name System, but parsed in the message routing logic of mail transport agents (MTA). It was introduced in 1985.
     */
    public static final int CSNET = 2;

    /**
     * Chaos network DNS resource record class: {@code CH} (MIT)
     */
    public static final int CH = 3;

    /**
     * Chaos network DNS resource record class: {@code CHAOS} (MIT, alternate name)
     */
    public static final int CHAOS = 3;

    /**
     * Hesiod DNS resource record class: {@code HS} (MIT)
     */
    public static final int HS = 4;

    /**
     * Hesiod DNS resource record class: {@code HESIOD} (MIT, alternate name)
     */
    public static final int HESIOD = 4;

    /**
     * Special value used in dynamic update messages
     */
    public static final int NONE = 254;

    /**
     * Matches any class
     */
    public static final int ANY = 255;



    private static Mnemonic classes = new DClassMnemonic();


    private static
    class DClassMnemonic extends Mnemonic {
        DClassMnemonic() {
            super("DnsClass", CASE_UPPER);
            setPrefix("CLASS");
        }

        @Override
        public
        void check(int val) {
            DnsClass.check(val);
        }
    }


    static {
        classes.add(IN, "IN");
        classes.add(CS, "CS");
        classes.addAlias(CSNET, "CSNET");
        classes.add(CH, "CH");
        classes.addAlias(CH, "CHAOS");
        classes.add(HS, "HS");
        classes.addAlias(HS, "HESIOD");
        classes.add(NONE, "NONE");
        classes.add(ANY, "ANY");
    }

    private
    DnsClass() {}

    /**
     * Checks that a numeric DnsClass is valid.
     *
     * @throws InvalidDClassException The class is out of range.
     */
    public static
    void check(int i) {
        if (i < 0 || i > 0xFFFF) {
            throw new InvalidDClassException(i);
        }
    }

    /**
     * Converts a numeric DnsClass into a String
     *
     * @return The canonical string representation of the class
     *
     * @throws InvalidDClassException The class is out of range.
     */
    public static
    String string(int i) {
        return classes.getText(i);
    }

    /**
     * Converts a String representation of a DnsClass into its numeric value
     *
     * @return The class code, or -1 on error.
     */
    public static
    int value(String s) {
        return classes.getValue(s);
    }

}
