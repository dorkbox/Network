// Copyright (c) 1999-2004 Brian Wellington (bwelling@xbill.org)

package dorkbox.network.dns.records;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Date;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.esotericsoftware.kryo.util.ObjectMap;

import dorkbox.network.dns.DnsOutput;
import dorkbox.network.dns.Name;
import dorkbox.network.dns.constants.DnsClass;
import dorkbox.network.dns.constants.DnsResponseCode;
import dorkbox.network.dns.constants.DnsSection;
import dorkbox.network.dns.exceptions.TextParseException;
import dorkbox.network.dns.utils.Options;
import dorkbox.util.Base64Fast;

/**
 * Transaction signature handling.  This class generates and verifies
 * TSIG records on messages, which provide transaction security.
 *
 * @author Brian Wellington
 * @see TSIGRecord
 */

@SuppressWarnings("WeakerAccess")
public
class TSIG {

    /**
     * The domain name representing the HMAC-MD5 algorithm.
     */
    public static final Name HMAC_MD5 = Name.fromConstantString("HMAC-MD5.SIG-ALG.REG.INT.");

    /**
     * The domain name representing the HMAC-MD5 algorithm (deprecated).
     */
    public static final Name HMAC = HMAC_MD5;

    /**
     * The domain name representing the HMAC-SHA1 algorithm.
     */
    public static final Name HMAC_SHA1 = Name.fromConstantString("hmac-sha1.");

    /**
     * The domain name representing the HMAC-SHA224 algorithm.
     * Note that SHA224 is not supported by Java out-of-the-box, this requires use
     * of a third party provider like BouncyCastle.org.
     */
    public static final Name HMAC_SHA224 = Name.fromConstantString("hmac-sha224.");

    /**
     * The domain name representing the HMAC-SHA256 algorithm.
     */
    public static final Name HMAC_SHA256 = Name.fromConstantString("hmac-sha256.");

    /**
     * The domain name representing the HMAC-SHA384 algorithm.
     */
    public static final Name HMAC_SHA384 = Name.fromConstantString("hmac-sha384.");

    /**
     * The domain name representing the HMAC-SHA512 algorithm.
     */
    public static final Name HMAC_SHA512 = Name.fromConstantString("hmac-sha512.");

    private static final ObjectMap<Name, String> algMap = new ObjectMap<Name, String>();

    /**
     * The default fudge value for outgoing packets.  Can be overridden by the
     * tsigfudge option.
     */
    public static final short FUDGE = 300;
    private Name name, alg;
    private Mac hmac;

    static {
        algMap.put(HMAC_MD5, "HmacMD5");
        algMap.put(HMAC_SHA1, "HmacSHA1");
        algMap.put(HMAC_SHA224, "HmacSHA224");
        algMap.put(HMAC_SHA256, "HmacSHA256");
        algMap.put(HMAC_SHA384, "HmacSHA384");
        algMap.put(HMAC_SHA512, "HmacSHA512");
    }

    /**
     * Verifies the data (computes the secure hash and compares it to the input)
     *
     * @param mac The HMAC generator
     * @param signature The signature to compare against
     *
     * @return true if the signature matches, false otherwise
     */
    private static
    boolean verify(Mac mac, byte[] signature) {
        return verify(mac, signature, false);
    }

    /**
     * Verifies the data (computes the secure hash and compares it to the input)
     *
     * @param mac The HMAC generator
     * @param signature The signature to compare against
     * @param truncation_ok If true, the signature may be truncated; only the
     *         number of bytes in the provided signature are compared.
     *
     * @return true if the signature matches, false otherwise
     */
    private static
    boolean verify(Mac mac, byte[] signature, boolean truncation_ok) {
        byte[] expected = mac.doFinal();
        if (truncation_ok && signature.length < expected.length) {
            byte[] truncated = new byte[signature.length];
            System.arraycopy(expected, 0, truncated, 0, truncated.length);
            expected = truncated;
        }
        return Arrays.equals(signature, expected);
    }

    /**
     * Creates a new TSIG key, which can be used to sign or verify a message.
     *
     * @param algorithm The algorithm of the shared key.
     * @param name The name of the shared key.
     * @param key The shared key.
     */
    public
    TSIG(Name algorithm, Name name, SecretKey key) {
        this.name = name;
        this.alg = algorithm;
        String macAlgorithm = nameToAlgorithm(algorithm);
        init_hmac(macAlgorithm, key);
    }

    public static
    String nameToAlgorithm(Name name) {
        String alg = algMap.get(name);
        if (alg != null) {
            return alg;
        }
        throw new IllegalArgumentException("Unknown algorithm");
    }

    private
    void init_hmac(String macAlgorithm, SecretKey key) {
        try {
            hmac = Mac.getInstance(macAlgorithm);
            hmac.init(key);
        } catch (GeneralSecurityException ex) {
            throw new IllegalArgumentException("Caught security " + "exception setting up " + "HMAC.");
        }
    }

    /**
     * Creates a new TSIG key from a pre-initialized Mac instance.
     * This assumes that init() has already been called on the mac
     * to set up the key.
     *
     * @param mac The JCE HMAC object
     * @param name The name of the key
     */
    public
    TSIG(Mac mac, Name name) {
        this.name = name;
        this.hmac = mac;
        this.alg = algorithmToName(mac.getAlgorithm());
    }

    public static
    Name algorithmToName(String alg) {

        // false identity check because it's string comparisons.
        Name foundKey = algMap.findKey(alg, false);
        if (foundKey != null) {
            return foundKey;
        }

        throw new IllegalArgumentException("Unknown algorithm");
    }

    /**
     * Creates a new TSIG key with the hmac-md5 algorithm, which can be used to
     * sign or verify a message.
     *
     * @param name The name of the shared key.
     * @param key The shared key's data.
     */
    public
    TSIG(Name name, byte[] key) {
        this(HMAC_MD5, name, key);
    }

    /**
     * Creates a new TSIG key, which can be used to sign or verify a message.
     *
     * @param algorithm The algorithm of the shared key.
     * @param name The name of the shared key.
     * @param keyBytes The shared key's data.
     */
    public
    TSIG(Name algorithm, Name name, byte[] keyBytes) {
        this.name = name;
        this.alg = algorithm;
        String macAlgorithm = nameToAlgorithm(algorithm);
        SecretKey key = new SecretKeySpec(keyBytes, macAlgorithm);
        init_hmac(macAlgorithm, key);
    }

    /**
     * Creates a new TSIG object, which can be used to sign or verify a message.
     *
     * @param name The name of the shared key.
     * @param algorithm The algorithm of the shared key.  The legal values are
     *         "hmac-md5", "hmac-sha1", "hmac-sha224", "hmac-sha256", "hmac-sha384", and
     *         "hmac-sha512".
     * @param key The shared key's data represented as a base64 encoded string.
     *
     * @throws IllegalArgumentException The key name is an invalid name
     * @throws IllegalArgumentException The key data is improperly encoded
     */
    public
    TSIG(String algorithm, String name, String key) {
        this(algorithmToName(algorithm), name, key);
    }

    /**
     * Creates a new TSIG object, which can be used to sign or verify a message.
     *
     * @param name The name of the shared key.
     * @param key The shared key's data represented as a base64 encoded string.
     *
     * @throws IllegalArgumentException The key name is an invalid name
     * @throws IllegalArgumentException The key data is improperly encoded
     */
    public
    TSIG(Name algorithm, String name, String key) {
        byte[] keyBytes;
        try {
            keyBytes = Base64Fast.decode2(key);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid TSIG key string");
        }

        if (keyBytes == null) {
            throw new IllegalArgumentException("Invalid TSIG key string");
        }

        try {
            this.name = Name.fromString(name, Name.root);
        } catch (TextParseException e) {
            throw new IllegalArgumentException("Invalid TSIG key name");
        }
        this.alg = algorithm;
        String macAlgorithm = nameToAlgorithm(this.alg);
        init_hmac(macAlgorithm, new SecretKeySpec(keyBytes, macAlgorithm));
    }

    /**
     * Creates a new TSIG object with the hmac-md5 algorithm, which can be used to
     * sign or verify a message.
     *
     * @param name The name of the shared key
     * @param key The shared key's data, represented as a base64 encoded string.
     *
     * @throws IllegalArgumentException The key name is an invalid name
     * @throws IllegalArgumentException The key data is improperly encoded
     */
    public
    TSIG(String name, String key) {
        this(HMAC_MD5, name, key);
    }

    /**
     * Creates a new TSIG object, which can be used to sign or verify a message.
     *
     * @param str The TSIG key, in the form name:secret, name/secret,
     *         alg:name:secret, or alg/name/secret.  If an algorithm is specified, it must
     *         be "hmac-md5", "hmac-sha1", or "hmac-sha256".
     *
     * @throws IllegalArgumentException The string does not contain both a name
     *         and secret.
     * @throws IllegalArgumentException The key name is an invalid name
     * @throws IllegalArgumentException The key data is improperly encoded
     */
    static public
    TSIG fromString(String str) {
        String[] parts = str.split("[:/]", 3);
        if (parts.length < 2) {
            throw new IllegalArgumentException("Invalid TSIG key " + "specification");
        }
        if (parts.length == 3) {
            try {
                return new TSIG(parts[0], parts[1], parts[2]);
            } catch (IllegalArgumentException e) {
                parts = str.split("[:/]", 2);
            }
        }
        return new TSIG(HMAC_MD5, parts[0], parts[1]);
    }

    /**
     * Generates a TSIG record for a message and adds it to the message
     *
     * @param m The message
     * @param old If this message is a response, the TSIG from the request
     */
    public
    void applyStream(DnsMessage m, TSIGRecord old, boolean first) {
        if (first) {
            apply(m, old);
            return;
        }
        Date timeSigned = new Date();
        int fudge;
        hmac.reset();

        fudge = Options.intValue("tsigfudge");
        if (fudge < 0 || fudge > 0x7FFF) {
            fudge = FUDGE;
        }

        DnsOutput out = new DnsOutput();
        out.writeU16(old.getSignature().length);
        hmac.update(out.toByteArray());
        hmac.update(old.getSignature());

	/* Digest the message */
        hmac.update(m.toWire());

        out = new DnsOutput();
        long time = timeSigned.getTime() / 1000;
        int timeHigh = (int) (time >> 32);
        long timeLow = (time & 0xFFFFFFFFL);
        out.writeU16(timeHigh);
        out.writeU32(timeLow);
        out.writeU16(fudge);

        hmac.update(out.toByteArray());

        byte[] signature = hmac.doFinal();
        byte[] other = null;

        DnsRecord r = new TSIGRecord(name,
                                     DnsClass.ANY,
                                     0,
                                     alg,
                                     timeSigned,
                                     fudge,
                                     signature,
                                     m.getHeader()
                                   .getID(),
                                     DnsResponseCode.NOERROR,
                                     other);
        m.addRecord(r, DnsSection.ADDITIONAL);
        m.tsigState = DnsMessage.TSIG_SIGNED;
    }

    /**
     * Generates a TSIG record for a message and adds it to the message
     *
     * @param m The message
     * @param old If this message is a response, the TSIG from the request
     */
    public
    void apply(DnsMessage m, TSIGRecord old) {
        apply(m, DnsResponseCode.NOERROR, old);
    }

    /**
     * Generates a TSIG record with a specific error for a message and adds it
     * to the message.
     *
     * @param m The message
     * @param error The error
     * @param old If this message is a response, the TSIG from the request
     */
    public
    void apply(DnsMessage m, int error, TSIGRecord old) {
        DnsRecord r = generate(m, m.toWire(), error, old);
        m.addRecord(r, DnsSection.ADDITIONAL);
        m.tsigState = DnsMessage.TSIG_SIGNED;
    }

    /**
     * Generates a TSIG record with a specific error for a message that has
     * been rendered.
     *
     * @param m The message
     * @param b The rendered message
     * @param error The error
     * @param old If this message is a response, the TSIG from the request
     *
     * @return The TSIG record to be added to the message
     */
    public
    TSIGRecord generate(DnsMessage m, byte[] b, int error, TSIGRecord old) {
        Date timeSigned;
        if (error != DnsResponseCode.BADTIME) {
            timeSigned = new Date();
        }
        else {
            timeSigned = old.getTimeSigned();
        }
        int fudge;
        boolean signing = false;
        if (error == DnsResponseCode.NOERROR || error == DnsResponseCode.BADTIME) {
            signing = true;
            hmac.reset();
        }

        fudge = Options.intValue("tsigfudge");
        if (fudge < 0 || fudge > 0x7FFF) {
            fudge = FUDGE;
        }

        if (old != null) {
            DnsOutput out = new DnsOutput();
            out.writeU16(old.getSignature().length);
            if (signing) {
                hmac.update(out.toByteArray());
                hmac.update(old.getSignature());
            }
        }

	/* Digest the message */
        if (signing) {
            hmac.update(b);
        }

        DnsOutput out = new DnsOutput();
        name.toWireCanonical(out);
        out.writeU16(DnsClass.ANY);	/* class */
        out.writeU32(0);		/* ttl */
        alg.toWireCanonical(out);
        long time = timeSigned.getTime() / 1000;
        int timeHigh = (int) (time >> 32);
        long timeLow = (time & 0xFFFFFFFFL);
        out.writeU16(timeHigh);
        out.writeU32(timeLow);
        out.writeU16(fudge);

        out.writeU16(error);
        out.writeU16(0); /* No other data */

        if (signing) {
            hmac.update(out.toByteArray());
        }

        byte[] signature;
        if (signing) {
            signature = hmac.doFinal();
        }
        else {
            signature = new byte[0];
        }

        byte[] other = null;
        if (error == DnsResponseCode.BADTIME) {
            out = new DnsOutput();
            time = new Date().getTime() / 1000;
            timeHigh = (int) (time >> 32);
            timeLow = (time & 0xFFFFFFFFL);
            out.writeU16(timeHigh);
            out.writeU32(timeLow);
            other = out.toByteArray();
        }

        return (new TSIGRecord(name,
                               DnsClass.ANY,
                               0,
                               alg,
                               timeSigned,
                               fudge,
                               signature,
                               m.getHeader()
                                .getID(),
                               error,
                               other));
    }

    /**
     * Verifies a TSIG record on an incoming message.  Since this is only called
     * in the context where a TSIG is expected to be present, it is an error
     * if one is not present.  After calling this routine, DnsMessage.isVerified() may
     * be called on this message.
     *
     * @param m The message
     * @param b The message in unparsed form.  This is necessary since TSIG
     *         signs the message in wire format, and we can't recreate the exact wire
     *         format (with the same name compression).
     * @param old If this message is a response, the TSIG from the request
     *
     * @return The result of the verification (as an DnsResponseCode)
     *
     * @see DnsResponseCode
     */
    public
    int verify(DnsMessage m, byte[] b, TSIGRecord old) {
        return verify(m, b, b.length, old);
    }

    /**
     * Verifies a TSIG record on an incoming message.  Since this is only called
     * in the context where a TSIG is expected to be present, it is an error
     * if one is not present.  After calling this routine, DnsMessage.isVerified() may
     * be called on this message.
     *
     * @param m The message
     * @param b An array containing the message in unparsed form.  This is
     *         necessary since TSIG signs the message in wire format, and we can't
     *         recreate the exact wire format (with the same name compression).
     * @param length The length of the message in the array.
     * @param old If this message is a response, the TSIG from the request
     *
     * @return The result of the verification (as an DnsResponseCode)
     *
     * @see DnsResponseCode
     */
    public
    byte verify(DnsMessage m, byte[] b, int length, TSIGRecord old) {
        m.tsigState = DnsMessage.TSIG_FAILED;
        TSIGRecord tsig = m.getTSIG();
        hmac.reset();
        if (tsig == null) {
            return DnsResponseCode.FORMERR;
        }

        if (!tsig.getName()
                 .equals(name) || !tsig.getAlgorithm()
                                       .equals(alg)) {
            if (Options.check("verbose")) {
                System.err.println("BADKEY failure");
            }
            return DnsResponseCode.BADKEY;
        }
        long now = System.currentTimeMillis();
        long then = tsig.getTimeSigned()
                        .getTime();
        long fudge = tsig.getFudge();
        if (Math.abs(now - then) > fudge * 1000) {
            if (Options.check("verbose")) {
                System.err.println("BADTIME failure");
            }
            return DnsResponseCode.BADTIME;
        }

        if (old != null && tsig.getError() != DnsResponseCode.BADKEY && tsig.getError() != DnsResponseCode.BADSIG) {
            DnsOutput out = new DnsOutput();
            out.writeU16(old.getSignature().length);
            hmac.update(out.toByteArray());
            hmac.update(old.getSignature());
        }
        m.getHeader()
         .decCount(DnsSection.ADDITIONAL);
        byte[] header = m.getHeader()
                         .toWire();
        m.getHeader()
         .incCount(DnsSection.ADDITIONAL);
        hmac.update(header);

        int len = m.tsigstart - header.length;
        hmac.update(b, header.length, len);

        DnsOutput out = new DnsOutput();
        tsig.getName()
            .toWireCanonical(out);
        out.writeU16(tsig.dclass);
        out.writeU32(tsig.ttl);
        tsig.getAlgorithm()
            .toWireCanonical(out);
        long time = tsig.getTimeSigned()
                        .getTime() / 1000;
        int timeHigh = (int) (time >> 32);
        long timeLow = (time & 0xFFFFFFFFL);
        out.writeU16(timeHigh);
        out.writeU32(timeLow);
        out.writeU16(tsig.getFudge());
        out.writeU16(tsig.getError());
        if (tsig.getOther() != null) {
            out.writeU16(tsig.getOther().length);
            out.writeByteArray(tsig.getOther());
        }
        else {
            out.writeU16(0);
        }

        hmac.update(out.toByteArray());

        byte[] signature = tsig.getSignature();
        int digestLength = hmac.getMacLength();
        int minDigestLength;
        if (hmac.getAlgorithm()
                .toLowerCase()
                .contains("md5")) {
            minDigestLength = 10;
        }
        else {
            minDigestLength = digestLength / 2;
        }

        if (signature.length > digestLength) {
            if (Options.check("verbose")) {
                System.err.println("BADSIG: signature too long");
            }
            return DnsResponseCode.BADSIG;
        }
        else if (signature.length < minDigestLength) {
            if (Options.check("verbose")) {
                System.err.println("BADSIG: signature too short");
            }
            return DnsResponseCode.BADSIG;
        }
        else if (!verify(hmac, signature, true)) {
            if (Options.check("verbose")) {
                System.err.println("BADSIG: signature verification");
            }
            return DnsResponseCode.BADSIG;
        }

        m.tsigState = DnsMessage.TSIG_VERIFIED;
        return DnsResponseCode.NOERROR;
    }

    /**
     * Returns the maximum length of a TSIG record generated by this key.
     *
     * @see TSIGRecord
     */
    public
    int recordLength() {
        return (name.length() + 10 + alg.length() + 8 +    // time signed, fudge
                18 +    // 2 byte MAC length, 16 byte MAC
                4 +    // original id, error
                8);    // 2 byte error length, 6 byte max error field.
    }

    public static
    class StreamVerifier {
        /**
         * A helper class for verifying multiple message responses.
         */

        private TSIG key;
        private Mac verifier;
        private int nresponses;
        private int lastsigned;
        private TSIGRecord lastTSIG;

        /**
         * Creates an object to verify a multiple message response
         */
        public
        StreamVerifier(TSIG tsig, TSIGRecord old) {
            key = tsig;
            verifier = tsig.hmac;
            nresponses = 0;
            lastTSIG = old;
        }

        /**
         * Verifies a TSIG record on an incoming message that is part of a
         * multiple message response.
         * TSIG records must be present on the first and last messages, and
         * at least every 100 records in between.
         * After calling this routine, DnsMessage.isVerified() may be called on
         * this message.
         *
         * @param m The message
         * @param b The message in unparsed form
         *
         * @return The result of the verification (as an DnsResponseCode)
         *
         * @see DnsResponseCode
         */
        public
        int verify(DnsMessage m, byte[] b) {
            TSIGRecord tsig = m.getTSIG();

            nresponses++;

            if (nresponses == 1) {
                int result = key.verify(m, b, lastTSIG);
                if (result == DnsResponseCode.NOERROR) {
                    byte[] signature = tsig.getSignature();
                    DnsOutput out = new DnsOutput();
                    out.writeU16(signature.length);
                    verifier.update(out.toByteArray());
                    verifier.update(signature);
                }
                lastTSIG = tsig;
                return result;
            }

            if (tsig != null) {
                m.getHeader()
                 .decCount(DnsSection.ADDITIONAL);
            }
            byte[] header = m.getHeader()
                             .toWire();
            if (tsig != null) {
                m.getHeader()
                 .incCount(DnsSection.ADDITIONAL);
            }
            verifier.update(header);

            int len;
            if (tsig == null) {
                len = b.length - header.length;
            }
            else {
                len = m.tsigstart - header.length;
            }
            verifier.update(b, header.length, len);

            if (tsig != null) {
                lastsigned = nresponses;
                lastTSIG = tsig;
            }
            else {
                boolean required = (nresponses - lastsigned >= 100);
                if (required) {
                    m.tsigState = DnsMessage.TSIG_FAILED;
                    return DnsResponseCode.FORMERR;
                }
                else {
                    m.tsigState = DnsMessage.TSIG_INTERMEDIATE;
                    return DnsResponseCode.NOERROR;
                }
            }

            if (!tsig.getName()
                     .equals(key.name) || !tsig.getAlgorithm()
                                               .equals(key.alg)) {
                if (Options.check("verbose")) {
                    System.err.println("BADKEY failure");
                }
                m.tsigState = DnsMessage.TSIG_FAILED;
                return DnsResponseCode.BADKEY;
            }

            DnsOutput out = new DnsOutput();
            long time = tsig.getTimeSigned()
                            .getTime() / 1000;
            int timeHigh = (int) (time >> 32);
            long timeLow = (time & 0xFFFFFFFFL);
            out.writeU16(timeHigh);
            out.writeU32(timeLow);
            out.writeU16(tsig.getFudge());
            verifier.update(out.toByteArray());

            if (TSIG.verify(verifier, tsig.getSignature()) == false) {
                if (Options.check("verbose")) {
                    System.err.println("BADSIG failure");
                }
                m.tsigState = DnsMessage.TSIG_FAILED;
                return DnsResponseCode.BADSIG;
            }

            verifier.reset();
            out = new DnsOutput();
            out.writeU16(tsig.getSignature().length);
            verifier.update(out.toByteArray());
            verifier.update(tsig.getSignature());

            m.tsigState = DnsMessage.TSIG_VERIFIED;
            return DnsResponseCode.NOERROR;
        }
    }

}
