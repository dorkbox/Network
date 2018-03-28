/*
 * Copyright 2018 dorkbox, llc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dorkbox.network.dns.constants;

import dorkbox.network.dns.Mnemonic;

/**
 * Constants and functions relating to EDNS flags.
 *
 * @author Brian Wellington
 */

public
enum ExtendedFlags {


    /**
     * dnssec ok
     */
    DO(0x8000, "do");

    private static Mnemonic extflags = new Mnemonic("EDNS Flag", Mnemonic.CASE_LOWER);
    static {
        extflags.setMaximum(0xFFFF);
        extflags.setPrefix("FLAG");
        extflags.setNumericAllowed(true);

        extflags.add(DO.flagValue, "do");
    }


    private final byte flagValue;
    private final String textValue;

    ExtendedFlags(final int flagValue, final String textValue) {
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

    /**
     * Converts a numeric extended flag into a String
     */
    public static
    String string(int i) {
        return extflags.getText(i);
    }

    /**
     * Converts a textual representation of an extended flag into its numeric
     * value
     */
    public static
    int value(String s) {
        return extflags.getValue(s);
    }

}
