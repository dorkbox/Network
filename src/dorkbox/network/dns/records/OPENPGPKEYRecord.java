package dorkbox.network.dns.records;

import java.io.IOException;

import dorkbox.network.dns.Compression;
import dorkbox.network.dns.DnsInput;
import dorkbox.network.dns.DnsOutput;
import dorkbox.network.dns.Name;
import dorkbox.network.dns.constants.DnsRecordType;
import dorkbox.network.dns.utils.Options;
import dorkbox.network.dns.utils.Tokenizer;
import dorkbox.util.Base64Fast;
import dorkbox.util.OS;

/**
 * OPENPGPKEY Record - Stores an OpenPGP certificate associated with a name.
 * RFC 7929.
 *
 * @author Brian Wellington
 * @author Valentin Hauner
 */
public
class OPENPGPKEYRecord extends DnsRecord {

    private static final long serialVersionUID = -1277262990243423062L;

    private byte[] cert;

    OPENPGPKEYRecord() {}

    @Override
    DnsRecord getObject() {
        return new OPENPGPKEYRecord();
    }

    @Override
    void rrFromWire(DnsInput in) throws IOException {
        cert = in.readByteArray();
    }

    @Override
    void rrToWire(DnsOutput out, Compression c, boolean canonical) {
        out.writeByteArray(cert);
    }

    /**
     * Converts rdata to a String
     */
    @Override
    void rrToString(StringBuilder sb) {
        if (cert != null) {
            if (Options.check("multiline")) {
                sb.append("(")
                  .append(OS.LINE_SEPARATOR);
                sb.append(Base64Fast.formatString(Base64Fast.encode2(cert), 64, "\t", true));
            }
            else {
                sb.append("\t");
                sb.append(Base64Fast.encode2(cert));
            }
        }
    }

    @Override
    void rdataFromString(Tokenizer st, Name origin) throws IOException {
        cert = st.getBase64();
    }

    /**
     * Creates an OPENPGPKEY Record from the given data
     *
     * @param cert Binary data representing the certificate
     */
    public
    OPENPGPKEYRecord(Name name, int dclass, long ttl, byte[] cert) {
        super(name, DnsRecordType.OPENPGPKEY, dclass, ttl);
        this.cert = cert;
    }

    /**
     * Returns the binary representation of the certificate
     */
    public
    byte[] getCert() {
        return cert;
    }

}
