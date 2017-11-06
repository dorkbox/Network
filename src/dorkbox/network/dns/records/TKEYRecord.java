// Copyright (c) 1999-2004 Brian Wellington (bwelling@xbill.org)

package dorkbox.network.dns.records;

import java.io.IOException;
import java.util.Date;

import dorkbox.network.dns.Compression;
import dorkbox.network.dns.DnsInput;
import dorkbox.network.dns.DnsOutput;
import dorkbox.network.dns.Name;
import dorkbox.network.dns.constants.DnsRecordType;
import dorkbox.network.dns.constants.DnsResponseCode;
import dorkbox.network.dns.utils.FormattedTime;
import dorkbox.network.dns.utils.Options;
import dorkbox.network.dns.utils.Tokenizer;
import dorkbox.util.Base64Fast;
import dorkbox.util.OS;

/**
 * Transaction Key - used to compute and/or securely transport a shared
 * secret to be used with TSIG.
 *
 * @author Brian Wellington
 * @see TSIG
 */

public
class TKEYRecord extends DnsRecord {

    private static final long serialVersionUID = 8828458121926391756L;

    private Name alg;
    private Date timeInception;
    private Date timeExpire;
    private int mode, error;
    private byte[] key;
    private byte[] other;

    /**
     * The key is assigned by the server (unimplemented)
     */
    public static final int SERVERASSIGNED = 1;

    /**
     * The key is computed using a Diffie-Hellman key exchange
     */
    public static final int DIFFIEHELLMAN = 2;

    /**
     * The key is computed using GSS_API (unimplemented)
     */
    public static final int GSSAPI = 3;

    /**
     * The key is assigned by the resolver (unimplemented)
     */
    public static final int RESOLVERASSIGNED = 4;

    /**
     * The key should be deleted
     */
    public static final int DELETE = 5;

    TKEYRecord() {}

    @Override
    DnsRecord getObject() {
        return new TKEYRecord();
    }

    @Override
    void rrFromWire(DnsInput in) throws IOException {
        alg = new Name(in);
        timeInception = new Date(1000 * in.readU32());
        timeExpire = new Date(1000 * in.readU32());
        mode = in.readU16();
        error = in.readU16();

        int keylen = in.readU16();
        if (keylen > 0) {
            key = in.readByteArray(keylen);
        }
        else {
            key = null;
        }

        int otherlen = in.readU16();
        if (otherlen > 0) {
            other = in.readByteArray(otherlen);
        }
        else {
            other = null;
        }
    }

    @Override
    void rrToWire(DnsOutput out, Compression c, boolean canonical) {
        alg.toWire(out, null, canonical);

        out.writeU32(timeInception.getTime() / 1000);
        out.writeU32(timeExpire.getTime() / 1000);

        out.writeU16(mode);
        out.writeU16(error);

        if (key != null) {
            out.writeU16(key.length);
            out.writeByteArray(key);
        }
        else {
            out.writeU16(0);
        }

        if (other != null) {
            out.writeU16(other.length);
            out.writeByteArray(other);
        }
        else {
            out.writeU16(0);
        }
    }

    /**
     * Converts rdata to a String
     */
    @Override
    void rrToString(StringBuilder sb) {
        sb.append(alg);
        sb.append(" ");
        if (Options.check("multiline")) {
            sb.append("(")
              .append(OS.LINE_SEPARATOR)
              .append("\t");
        }
        sb.append(FormattedTime.format(timeInception));
        sb.append(" ");
        sb.append(FormattedTime.format(timeExpire));
        sb.append(" ");
        sb.append(modeString());
        sb.append(" ");
        sb.append(DnsResponseCode.TSIGstring(error));
        if (Options.check("multiline")) {
            sb.append(OS.LINE_SEPARATOR);
            if (key != null) {
                sb.append(Base64Fast.formatString(Base64Fast.encode2(key), 64, "\t", true));
                sb.append(OS.LINE_SEPARATOR);
            }
            if (other != null) {
                sb.append(Base64Fast.formatString(Base64Fast.encode2(other), 64, "\t", true));
            }
            sb.append(" )");
        }
        else {
            sb.append(" ");
            if (key != null) {
                sb.append("\t");
                sb.append(Base64Fast.encode2(key));

                sb.append(" ");
            }
            if (other != null) {
                sb.append("\t");
                sb.append(Base64Fast.encode2(other));
            }
        }
    }

    @Override
    void rdataFromString(Tokenizer st, Name origin) throws IOException {
        throw st.exception("no text format defined for TKEY");
    }

    protected
    String modeString() {
        switch (mode) {
            case SERVERASSIGNED:
                return "SERVERASSIGNED";
            case DIFFIEHELLMAN:
                return "DIFFIEHELLMAN";
            case GSSAPI:
                return "GSSAPI";
            case RESOLVERASSIGNED:
                return "RESOLVERASSIGNED";
            case DELETE:
                return "DELETE";
            default:
                return Integer.toString(mode);
        }
    }

    /**
     * Creates a TKEY Record from the given data.
     *
     * @param alg The shared key's algorithm
     * @param timeInception The beginning of the validity period of the shared
     *         secret or keying material
     * @param timeExpire The end of the validity period of the shared
     *         secret or keying material
     * @param mode The mode of key agreement
     * @param error The extended error field.  Should be 0 in queries
     * @param key The shared secret
     * @param other The other data field.  Currently unused
     *         responses.
     */
    public
    TKEYRecord(Name name,
               int dclass,
               long ttl,
               Name alg,
               Date timeInception,
               Date timeExpire,
               int mode,
               int error,
               byte[] key,
               byte other[]) {
        super(name, DnsRecordType.TKEY, dclass, ttl);
        this.alg = checkName("alg", alg);
        this.timeInception = timeInception;
        this.timeExpire = timeExpire;
        this.mode = checkU16("mode", mode);
        this.error = checkU16("error", error);
        this.key = key;
        this.other = other;
    }

    /**
     * Returns the shared key's algorithm
     */
    public
    Name getAlgorithm() {
        return alg;
    }

    /**
     * Returns the beginning of the validity period of the shared secret or
     * keying material
     */
    public
    Date getTimeInception() {
        return timeInception;
    }

    /**
     * Returns the end of the validity period of the shared secret or
     * keying material
     */
    public
    Date getTimeExpire() {
        return timeExpire;
    }

    /**
     * Returns the key agreement mode
     */
    public
    int getMode() {
        return mode;
    }

    /**
     * Returns the extended error
     */
    public
    int getError() {
        return error;
    }

    /**
     * Returns the shared secret or keying material
     */
    public
    byte[] getKey() {
        return key;
    }

    /**
     * Returns the other data
     */
    public
    byte[] getOther() {
        return other;
    }

}
