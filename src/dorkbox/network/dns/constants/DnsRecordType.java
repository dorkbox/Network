// Copyright (c) 1999-2004 Brian Wellington (bwelling@xbill.org)

package dorkbox.network.dns.constants;

import dorkbox.network.dns.Mnemonic;
import dorkbox.network.dns.exceptions.InvalidTypeException;
import dorkbox.network.dns.records.DnsRecord;
import dorkbox.network.dns.records.DnsTypeProtoAssignment;
import dorkbox.util.collections.IntMap;
import io.netty.util.internal.StringUtil;

/**
 * Constants and functions relating to DNS Types
 *
 * @author Brian Wellington
 */

public final
class DnsRecordType {

    /**
     * Address record RFC 1035 Returns a 32-bit IPv4 address, most commonly used
     * to map hostnames to an IP address of the host, but also used for DNSBLs,
     * storing subnet masks in RFC 1101, etc.
     */
    public static final int A = 1;

    /**
     * Name server record RFC 1035 Delegates a DNS zone to use the given
     * authoritative name servers
     */
    public static final int NS = 2;

    /**
     * Mail destination. MD specifies the final destination to which a message addressed to a given domain name should be delivered
     * (Obsolete, as it's behavior has been replaced by MX)
     */
    @Deprecated
    public static final int MD = 3;

    /**
     * Mail forwarder. MF specifies a host that would forward mail on to the eventual destination, should that destination be unreachable.
     * (Obsolete, as it's behavior has been replaced by MX)
     */
    @Deprecated
    public static final int MF = 4;

    /**
     * Canonical name (alias) record RFC 1035 Alias of one name to another: the DNS
     * lookup will continue by retrying the lookup with the new name.
     */
    public static final int CNAME = 5;

    /**
     * Start of [a zone of] authority record RFC 1035 and RFC 2308 Specifies
     * authoritative information about a DNS zone, including the primary name
     * server, the email of the domain administrator, the domain serial number,
     * and several timers relating to refreshing the zone.
     */
    public static final int SOA = 6;

    /**
     * Mailbox domain name RFC 1035. EXPERIMENTAL. A <domain-name> which specifies a host which has the
     * specified mailbox.
     */
    public static final int MB = 7;

    /**
     * Mail group member RFC 1035. EXPERIMENTAL. A <domain-name> which specifies a mailbox which is a
     * member of the mail group specified by the domain name. MG records cause no additional section processing.
     */
    public static final int MG = 8;

    /**
     * Mail rename name RFC 1035. EXPERIMENTAL. A <domain-name> which specifies a mailbox which is the
     * proper rename of the specified mailbox.
     */
    public static final int MR = 9;

    /**
     * Null record RFC 1035. EXPERIMENTAL. Anything at all may be in the RDATA field so long as it is 65535 octets
     * or less
     */
    public static final int NULL = 10;

    /**
     * The WKS record RFC 1035 is used to describe the well known services supported by
     * a particular protocol on a particular internet address.
     */
    public static final int WKS = 11;

    /**
     * Pointer record RFC 1035 Pointer to a canonical name. Unlike a CNAME, DNS
     * processing does NOT proceed, just the name is returned. The most common
     * use is for implementing reverse DNS lookups, but other uses include such
     * things as DNS-SD.
     */
    public static final int PTR = 12;

    /**
     * Host information HINFO RFC 1035. records are used to acquire general information about a host.  The
     * main use is for protocols such as FTP that can use special procedures when talking between machines
     * or operating systems of the same type.
     */
    public static final int HINFO = 13;

    /**
     * Mailbox or mail list information RFC 1035. EXPERIMENTAL. MINFO records cause no additional section processing.  Although these
     * records can be associated with a simple mailbox, they are usually used with a mailing list.
     */
    public static final int MINFO = 14;

    /**
     * Mail exchange (routing) record RFC 1035 Maps a domain name to a list of message transfer agents for that domain.
     */
    public static final int MX = 15;

    /**
     * Text record RFC 1035 Originally for arbitrary human-readable text in a
     * DNS record. Since the early 1990s, however, this record more often
     * carries machine-readable data, such as specified by RFC 1464,
     * opportunistic encryption, Sender Policy Framework, DKIM, DMARC DNS-SD,
     * etc.
     */
    public static final int TXT = 16;

    /**
     * Responsible person record RFC 1183 Information about the responsible
     * person(s) for the domain. Usually an email address with the @ replaced by
     * a .
     */
    public static final int RP = 17;

    /**
     * AFS cell database record RFC 1183 Location of database servers of an AFS cell.
     * This record is commonly used by AFS clients to contact AFS cells outside
     * their local domain. A subtype of this record is used by the obsolete
     * DCE/DFS file system.
     */
    public static final int AFSDB = 18;

    /**
     * X.25 calling address
     */
    public static final int X25 = 19;

    /**
     * ISDN calling address
     */
    public static final int ISDN = 20;

    /**
     * Router
     */
    public static final int RT = 21;

    /**
     * NSAP address
     */
    public static final int NSAP = 22;

    /**
     * Reverse NSAP address (deprecated)
     */
    @Deprecated
    public static final int NSAP_PTR = 23;

    /**
     * Signature record RFC 2535 Signature record used in SIG(0) (RFC 2931) and
     * TKEY (RFC 2930). RFC 3755 designated RRSIG as the replacement for SIG for
     * use within DNSSEC.
     */
    public static final int SIG = 24;

    /**
     * key record RFC 2535 and RFC 2930 Used only for SIG(0) (RFC 2931) and TKEY
     * (RFC 2930). RFC 3445 eliminated their use for application keys and
     * limited their use to DNSSEC. RFC 3755 designates DNSKEY as the
     * replacement within DNSSEC. RFC 4025 designates IPSECKEY as the
     * replacement for use with IPsec.
     */
    public static final int KEY = 25;

    /**
     * X.400 mail mapping
     */
    public static final int PX = 26;

    /**
     * Geographical position (withdrawn)
     */
    @Deprecated
    public static final int GPOS = 27;

    /**
     * IPv6 address record RFC 3596 Returns a 128-bit IPv6 address, most
     * commonly used to map hostnames to an IP address of the host.
     */
    public static final int AAAA = 28;

    /**
     * Location record RFC 1876 Specifies a geographical location associated
     * with a domain name.
     */
    public static final int LOC = 29;

    /**
     * Next valid name in zone
     */
    public static final int NXT = 30;

    /**
     * Endpoint identifier
     */
    public static final int EID = 31;

    /**
     * Nimrod locator
     */
    public static final int NIMLOC = 32;

    /**
     * Service selection locator RFC 2782 Generalized service location record, used for
     * newer protocols instead of creating protocol-specific records such as MX.
     */
    public static final int SRV = 33;

    /**
     * ATM address
     */
    public static final int ATMA = 34;

    /**
     * Naming Authority Pointer record RFC 3403 Allows regular expression based
     * rewriting of domain names which can then be used as URIs, further domain
     * names to lookups, etc.
     */
    public static final int NAPTR = 35;

    /**
     * Key eXchanger record RFC 2230 Used with some cryptographic systems (not
     * including DNSSEC) to identify a key management agent for the associated
     * domain-name. Note that this has nothing to do with DNS Security. It is
     * Informational status, rather than being on the IETF standards-track. It
     * has always had limited deployment, but is still in use.
     */
    public static final int KX = 36;

    /**
     * Certificate record RFC 4398 Stores PKIX, SPKI, PGP, etc.
     */
    public static final int CERT = 37;

    /**
     * IPv6 address (experimental)
     */
    public static final int A6 = 38;

    /**
     * Delegation name record RFC 2672 DNAME creates an alias for a name and all
     * its subnames, unlike CNAME, which aliases only the exact name in its
     * label. Like the CNAME record, the DNS lookup will continue by retrying
     * the lookup with the new name. This is also known as Non-terminal name redirection
     */
    public static final int DNAME = 39;

    /**
     * Options - contains EDNS metadata. Option record RFC 2671 This is a pseudo DNS
     * record type needed to support EDNS.
     */
    public static final int OPT = 41;

    /**
     * Address Prefix List record RFC 3123 Specify lists of address ranges, e.g.
     * in CIDR format, for various address families. Experimental.
     */
    public static final int APL = 42;

    /**
     * Delegation signer record RFC 4034 The record used to identify the DNSSEC
     * signing key of a delegated zone.
     */
    public static final int DS = 43;

    /**
     * SSH Public Key Fingerprint record RFC 4255 Resource record for publishing
     * SSH public host key fingerprints in the DNS System, in order to aid in
     * verifying the authenticity of the host. RFC 6594 defines ECC SSH keys and
     * SHA-256 hashes. See the IANA SSHFP RR parameters registry for details.
     */
    public static final int SSHFP = 44;

    /**
     * IPsec Key record RFC 4025 Key record that can be used with IPsec.
     */
    public static final int IPSECKEY = 45;

    /**
     * Resource Record Signature. DNSSEC signature record RFC 4034 Signature for a DNSSEC-secured record
     * set. Uses the same format as the SIG record.
     */
    public static final int RRSIG = 46;

    /**
     * Next Secure Name. Next-Secure record RFC 4034 Part of DNSSEC, used to prove a name does not
     * exist. Uses the same format as the (obsolete) NXT record.
     */
    public static final int NSEC = 47;

    /**
     * DNSSEC Key record RFC 4034 The key record used in DNSSEC. Uses the same
     * format as the KEY record.
     */
    public static final int DNSKEY = 48;

    /**
     * Dynamic Host Configuration Protocol (DHCP) ID. DHCP identifier record RFC 4701
     * Used in conjunction with the FQDN option to DHCP.
     */
    public static final int DHCID = 49;

    /**
     * Next SECure, 3rd edition, RFC 5155. An extension to DNSSEC that allows proof
     * of nonexistence for a name without permitting zonewalking.
     */
    public static final int NSEC3 = 50;

    /**
     * NSEC3 parameters record RFC 5155 Parameter record for use with NSEC3.
     */
    public static final int NSEC3PARAM = 51;

    /**
     * Transport Layer Security Authentication, draft-ietf-dane-protocol-23.
     * TLSA certificate association record RFC 6698 A record for DNS-based
     * Authentication of Named Entities (DANE). RFC 6698 defines The TLSA DNS
     * resource record is used to associate a TLS server certificate or public
     * key with the domain name where the record is found, thus forming a 'TLSA
     * certificate association'.
     */
    public static final int TLSA = 52;

    /**
     * S/MIME cert association, draft-ietf-dane-smime
     */
    public static final int SMIMEA = 53;


    /**
     * Host Identity Protocol record RFC 5205 Method of separating the end-point
     * identifier and locator roles of IP addresses.
     */
    public static final int HIP = 55;

    /**
     * OpenPGP Key, RFC 7929
     */
    public static final int OPENPGPKEY = 61;

    /**
     * Sender Policy Framework (experimental) record RFC 4408 Specified as part of the SPF
     * protocol as an alternative to of storing SPF data in TXT records. Uses
     * the same format as the earlier TXT record.
     */
    public static final int SPF = 99;

    /**
     * Transaction key - used to compute a shared secret or exchange a key.
     * Secret key record RFC 2930 A method of providing keying material to be
     * used with TSIG that is encrypted under the public key in an accompanying
     * KEY RR..
     */
    public static final int TKEY = 249;

    /**
     * Transaction Signature record RFC 2845 Can be used to authenticate dynamic
     * updates as coming from an approved client, or to authenticate responses
     * as coming from an approved recursive name server similar to DNSSEC.
     */
    public static final int TSIG = 250;

    /**
     * Incremental Zone Transfer record RFC 1996 Requests a zone transfer of the
     * given zone but only differences from a previous serial number. This
     * request may be ignored and a full (AXFR) sent in response if the
     * authoritative server is unable to fulfill the request due to
     * configuration or lack of required deltas.
     */
    public static final int IXFR = 251;

    /**
     * Authoritative Zone Transfer record RFC 1035 Transfer entire zone file
     * from the master name server to secondary name servers.
     */
    public static final int AXFR = 252;

    /**
     * Transfer mailbox records
     */
    public static final int MAILB = 253;

    /**
     * Transfer mail agent records
     */
    public static final int MAILA = 254;

    /**
     * Matches any type
     *
     * All cached records RFC 1035 Returns all records of all types known to the
     * name server. If the name server does not have any information on the
     * name, the request will be forwarded on. The records returned may not be
     * complete. For example, if there is both an A and an MX for a name, but
     * the name server has only the A record cached, only the A record will be
     * returned. Sometimes referred to as ANY, for example in Windows nslookup
     * and Wireshark.
     */
    public static final int ANY = 255;

    /**
     * URI
     *
     * @see <a href="http://tools.ietf.org/html/draft-faltstrom-uri-14">draft-faltstrom-uri-14</a>
     */
    public static final int URI = 256;

    /**
     * Certification Authority Authorization, RFC 6844. CA pinning,
     * constraining acceptable CAs for a host/domain.
     */
    public static final int CAA = 257;

    /**
     * DNSSEC Trust Authorities record N/A Part of a deployment proposal for
     * DNSSEC without a signed DNS root. See the IANA database and Weiler Spec
     * for details. Uses the same format as the DS record.
     */
    public static final int TA = 32768;

    /**
     * DNSSEC Lookaside Validation, RFC 4431. For publishing DNSSEC trust
     * anchors outside of the DNS delegation chain. Uses the same format as the
     * DS record. RFC 5074 describes a way of using these records.
     */
    public static final int DLV = 32769;
    private static TypeMnemonic types = new TypeMnemonic();


    public static
    class TypeMnemonic extends Mnemonic {
        private IntMap<DnsRecord> objects;

        public
        TypeMnemonic() {
            super("DnsRecordType", CASE_UPPER);
            setPrefix("TYPE");
            objects = new IntMap<DnsRecord>();
        }

        public
        void add(int value, String str, DnsRecord proto) {
            super.add(value, str);
            objects.put(value, proto);
        }

        @SuppressWarnings("unchecked")
        public
        <T extends DnsRecord> T getProto(int value) {
            check(value);
            return (T) objects.get(value);
        }

        @Override
        public
        void check(int val) {
            DnsRecordType.check(val);
        }
    }

    static {
        // this is so we don't have to make each type constructor public
        DnsTypeProtoAssignment.assign(types);
    }

    private
    DnsRecordType() {
    }

    /**
     * Checks that a numeric DnsRecordType is valid.
     *
     * @throws InvalidTypeException The type is out of range.
     */
    public static
    void check(int val) {
        if (val < 0 || val > 0xFFFF) {
            throw new InvalidTypeException(val);
        }
    }

    /**
     * Converts a numeric DnsRecordType into a String
     *
     * @param val The type value.
     *
     * @return The canonical string representation of the type
     *
     * @throws InvalidTypeException The type is out of range.
     */
    public static
    String string(int val) {
        return types.getText(val);
    }

    /**
     * Converts a String representation of an DnsRecordType into its numeric value
     *
     * @return The type code, or -1 on error.
     */
    public static
    int value(String s) {
        return value(s, false);
    }

    /**
     * Converts a String representation of an DnsRecordType into its numeric value.
     *
     * @param s The string representation of the type
     * @param numberok Whether a number will be accepted or not.
     *
     * @return The type code, or -1 on error.
     */
    public static
    int value(String s, boolean numberok) {
        int val = types.getValue(s);
        if (val == -1 && numberok) {
            val = types.getValue("TYPE" + s);
        }
        return val;
    }

    public static
    <T extends DnsRecord> T getProto(int val) {
        return types.getProto(val);
    }

    /**
     * Is this type valid for a record (a non-meta type)?
     */
    public static
    boolean isRR(int type) {
        switch (type) {
            case OPT:
            case TKEY:
            case TSIG:
            case IXFR:
            case AXFR:
            case MAILB:
            case MAILA:
            case ANY:
                return false;
            default:
                return true;
        }
    }

    private static final String ptrSuffix = ".in-addr.arpa";

    /**
     * Guarantees that the specified host name is a FQND. This depends on it's type, which must also be specified.
     *
     * @param type the resource record type
     * @param hostName the hostname
     *
     * @return the Fully Qualified Domain Name for this hostname, depending on it's type
     */
    public static
    String ensureFQDN(int type, String hostName) {
        // list of RecordTypes from: https://docs.aws.amazon.com/Route53/latest/DeveloperGuide/ResourceRecordTypes.html
        switch (type) {
            case PTR:
                if (!hostName.endsWith(ptrSuffix)) {
                    // PTR absolutely MUST end in '.in-addr.arpa' in order for the DNS server to understand it.
                    // in this case, hostname is an ip address
                    return hostName + ptrSuffix;
                }

            case A:
            case AAAA:
            case CAA:
            case CNAME:
            case MX:
            case NAPTR:
            case NS:
            case SOA:
            case SPF:
            case SRV:
            case TXT:
                // resolving a hostname -> ip address, the hostname MUST end in a dot
                if (!StringUtil.endsWith(hostName, '.')) {
                    return hostName + '.';
                } else {
                    return hostName;
                }

            default:
                return hostName;
        }
    }
}
