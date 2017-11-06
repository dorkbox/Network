// Copyright (c) 1999-2004 Brian Wellington (bwelling@xbill.org)

package dorkbox.network.dns.constants;

import dorkbox.network.dns.Mnemonic;

/**
 * Constants and functions relating to DNS message sections
 *
 * @author Brian Wellington
 */

public final
class DnsSection {
    public static final int TOTAL_SECTION_COUNT = 4;

    /**
     * The question (first) section
     */
    public static final int QUESTION = 0;

    /**
     * The answer (second) section
     */
    public static final int ANSWER = 1;

    /**
     * The authority (third) section
     */
    public static final int AUTHORITY = 2;

    /**
     * The additional (fourth) section
     */
    public static final int ADDITIONAL = 3;

/* Aliases for dynamic update */
    /**
     * The zone (first) section of a dynamic update message
     */
    public static final int ZONE = 0;

    /**
     * The prerequisite (second) section of a dynamic update message
     */
    public static final int PREREQ = 1;

    /**
     * The update (third) section of a dynamic update message
     */
    public static final int UPDATE = 2;

    private static Mnemonic sections = new Mnemonic("DnsMessage DnsSection", Mnemonic.CASE_LOWER);
    private static String[] longSections = new String[4];
    private static String[] updateSections = new String[4];

    static {
        sections.setMaximum(3);
        sections.setNumericAllowed(true);

        sections.add(QUESTION, "qd");
        sections.add(ANSWER, "an");
        sections.add(AUTHORITY, "au");
        sections.add(ADDITIONAL, "ad");

        longSections[QUESTION] = "QUESTIONS";
        longSections[ANSWER] = "ANSWERS";
        longSections[AUTHORITY] = "AUTHORITY RECORDS";
        longSections[ADDITIONAL] = "ADDITIONAL RECORDS";

        updateSections[ZONE] = "ZONE";
        updateSections[PREREQ] = "PREREQUISITES";
        updateSections[UPDATE] = "UPDATE RECORDS";
        updateSections[ADDITIONAL] = "ADDITIONAL RECORDS";
    }

    private
    DnsSection() {}

    /**
     * Converts a numeric DnsSection into an abbreviation String
     */
    public static
    String string(int i) {
        return sections.getText(i);
    }

    /**
     * Converts a numeric DnsSection into a full description String
     */
    public static
    String longString(int i) {
        sections.check(i);
        return longSections[i];
    }

    /**
     * Converts a numeric DnsSection into a full description String for an update
     * DnsMessage.
     */
    public static
    String updString(int i) {
        sections.check(i);
        return updateSections[i];
    }

    /**
     * Converts a String representation of a DnsSection into its numeric value
     */
    public static
    int value(String s) {
        return sections.getValue(s);
    }

}
