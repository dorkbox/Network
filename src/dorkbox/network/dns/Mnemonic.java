// Copyright (c) 2004 Brian Wellington (bwelling@xbill.org)

package dorkbox.network.dns;

import com.esotericsoftware.kryo.util.IntMap;

import dorkbox.util.collections.ObjectIntMap;

/**
 * A utility class for converting between numeric codes and mnemonics
 * for those codes.  Mnemonics are case insensitive.
 *
 * @author Brian Wellington
 */
public
class Mnemonic {

    /** Strings are case-sensitive. */
    public static final int CASE_SENSITIVE = 1;

    /** Strings will be stored/searched for in uppercase. */
    public static final int CASE_UPPER = 2;

    /** Strings will be stored/searched for in lowercase. */
    public static final int CASE_LOWER = 3;

    private static final int INVALID_VALUE = -1;

    private ObjectIntMap<String> strings;
    private IntMap<String> values;

    private String description;
    private int wordcase;
    private String prefix;
    private int max;
    private boolean numericok;

    /**
     * Creates a new Mnemonic table.
     *
     * @param description A short description of the mnemonic to use when
     * @param wordcase Whether to convert strings into uppercase, lowercase,
     *         or leave them unchanged.
     *         throwing exceptions.
     */
    public
    Mnemonic(String description, int wordcase) {
        this.description = description;
        this.wordcase = wordcase;
        strings = new ObjectIntMap<String>();
        values = new IntMap<String>();
        max = Integer.MAX_VALUE;
    }

    /**
     * Sets the maximum numeric value
     */
    public
    void setMaximum(int max) {
        this.max = max;
    }

    /**
     * Sets the prefix to use when converting to and from values that don't
     * have mnemonics.
     */
    public
    void setPrefix(String prefix) {
        this.prefix = sanitize(prefix);
    }

    /* Converts a String to the correct case. */
    private
    String sanitize(String str) {
        if (wordcase == CASE_UPPER) {
            return str.toUpperCase();
        }
        else if (wordcase == CASE_LOWER) {
            return str.toLowerCase();
        }
        return str;
    }

    /**
     * Sets whether numeric values stored in strings are acceptable.
     */
    public
    void setNumericAllowed(boolean numeric) {
        this.numericok = numeric;
    }

    /**
     * Defines the text representation of a numeric value.
     *
     * @param value The numeric value
     * @param string The text string
     */
    public
    void add(int value, String string) {
        check(value);
        string = sanitize(string);
        strings.put(string, value);
        values.put(value, string);
    }

    /**
     * Checks that a numeric value is within the range [0..max]
     */
    public
    void check(int val) {
        if (val < 0 || val > max) {
            throw new IllegalArgumentException(description + " " + val + "is out of range");
        }
    }

    /**
     * Defines an additional text representation of a numeric value.  This will
     * be used by getValue(), but not getText().
     *
     * @param value The numeric value
     * @param string The text string
     */
    public
    void addAlias(int value, String string) {
        check(value);
        string = sanitize(string);
        strings.put(string, value);
    }

    /**
     * Copies all mnemonics from one table into another.
     *
     * @param source The Mnemonic source to add from
     *
     * @throws IllegalArgumentException The wordcases of the Mnemonics do not
     *         match.
     */
    public
    void addAll(Mnemonic source) {
        if (wordcase != source.wordcase) {
            throw new IllegalArgumentException(source.description + ": wordcases do not match");
        }

        strings.putAll(source.strings);
        values.putAll(source.values);
    }

    /**
     * Gets the text mnemonic corresponding to a numeric value.
     *
     * @param value The numeric value
     *
     * @return The corresponding text mnemonic.
     */
    public
    String getText(int value) {
        check(value);
        String str = values.get(value);
        if (str != null) {
            return str;
        }

        str = Integer.toString(value);
        if (prefix != null) {
            return prefix + str;
        }
        return str;
    }

    /**
     * Gets the numeric value corresponding to a text mnemonic.
     *
     * @param str The text mnemonic
     *
     * @return The corresponding numeric value, or -1 if there is none
     */
    public
    int getValue(String str) {
        str = sanitize(str);
        int value = strings.get(str, INVALID_VALUE);

        if (value != INVALID_VALUE) {
            return value;
        }
        if (prefix != null) {
            if (str.startsWith(prefix)) {
                int val = parseNumeric(str.substring(prefix.length()));
                if (val >= 0) {
                    return val;
                }
            }
        }
        if (numericok) {
            return parseNumeric(str);
        }

        return INVALID_VALUE;
    }

    private
    int parseNumeric(String s) {
        try {
            int val = Integer.parseInt(s);
            if (val >= 0 && val <= max) {
                return val;
            }
        } catch (NumberFormatException ignored) {
        }

        return INVALID_VALUE;
    }
}
