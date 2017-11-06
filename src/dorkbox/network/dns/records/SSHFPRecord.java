// Copyright (c) 2004 Brian Wellington (bwelling@xbill.org)

package dorkbox.network.dns.records;

import java.io.IOException;

import dorkbox.network.dns.Compression;
import dorkbox.network.dns.DnsInput;
import dorkbox.network.dns.DnsOutput;
import dorkbox.network.dns.Name;
import dorkbox.network.dns.constants.DnsRecordType;
import dorkbox.network.dns.utils.Tokenizer;
import dorkbox.network.dns.utils.base16;

/**
 * SSH Fingerprint - stores the fingerprint of an SSH host key.
 *
 * @author Brian Wellington
 */

public
class SSHFPRecord extends DnsRecord {

    private static final long serialVersionUID = -8104701402654687025L;
    private int alg;
    private int digestType;
    private byte[] fingerprint;


    public static
    class Algorithm {
        public static final int RSA = 1;
        public static final int DSS = 2;
        private
        Algorithm() {}
    }


    public static
    class Digest {
        public static final int SHA1 = 1;

        private
        Digest() {}
    }

    SSHFPRecord() {}

    @Override
    DnsRecord getObject() {
        return new SSHFPRecord();
    }

    @Override
    void rrFromWire(DnsInput in) throws IOException {
        alg = in.readU8();
        digestType = in.readU8();
        fingerprint = in.readByteArray();
    }

    @Override
    void rrToWire(DnsOutput out, Compression c, boolean canonical) {
        out.writeU8(alg);
        out.writeU8(digestType);
        out.writeByteArray(fingerprint);
    }

    @Override
    void rrToString(StringBuilder sb) {
        sb.append(alg);
        sb.append(" ");
        sb.append(digestType);
        sb.append(" ");
        sb.append(base16.toString(fingerprint));
    }

    @Override
    void rdataFromString(Tokenizer st, Name origin) throws IOException {
        alg = st.getUInt8();
        digestType = st.getUInt8();
        fingerprint = st.getHex(true);
    }

    /**
     * Creates an SSHFP Record from the given data.
     *
     * @param alg The public key's algorithm.
     * @param digestType The public key's digest type.
     * @param fingerprint The public key's fingerprint.
     */
    public
    SSHFPRecord(Name name, int dclass, long ttl, int alg, int digestType, byte[] fingerprint) {
        super(name, DnsRecordType.SSHFP, dclass, ttl);
        this.alg = checkU8("alg", alg);
        this.digestType = checkU8("digestType", digestType);
        this.fingerprint = fingerprint;
    }

    /**
     * Returns the public key's algorithm.
     */
    public
    int getAlgorithm() {
        return alg;
    }

    /**
     * Returns the public key's digest type.
     */
    public
    int getDigestType() {
        return digestType;
    }

    /**
     * Returns the fingerprint
     */
    public
    byte[] getFingerPrint() {
        return fingerprint;
    }

}
