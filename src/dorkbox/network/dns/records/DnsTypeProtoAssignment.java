package dorkbox.network.dns.records;

import dorkbox.network.dns.constants.DnsRecordType;

public
class DnsTypeProtoAssignment {

    // this is so we don't have to make each type constructor public
    public static
    void assign(final DnsRecordType.TypeMnemonic types) {
        types.add(DnsRecordType.A, "A", new ARecord());
        types.add(DnsRecordType.NS, "NS", new NSRecord());
        types.add(DnsRecordType.MD, "MD", new MDRecord());
        types.add(DnsRecordType.MF, "MF", new MFRecord());
        types.add(DnsRecordType.CNAME, "CNAME", new CNAMERecord());
        types.add(DnsRecordType.SOA, "SOA", new SOARecord());
        types.add(DnsRecordType.MB, "MB", new MBRecord());
        types.add(DnsRecordType.MG, "MG", new MGRecord());
        types.add(DnsRecordType.MR, "MR", new MRRecord());
        types.add(DnsRecordType.NULL, "NULL", new NULLRecord());
        types.add(DnsRecordType.WKS, "WKS", new WKSRecord());
        types.add(DnsRecordType.PTR, "PTR", new PTRRecord());
        types.add(DnsRecordType.HINFO, "HINFO", new HINFORecord());
        types.add(DnsRecordType.MINFO, "MINFO", new MINFORecord());
        types.add(DnsRecordType.MX, "MX", new MXRecord());
        types.add(DnsRecordType.TXT, "TXT", new TXTRecord());
        types.add(DnsRecordType.RP, "RP", new RPRecord());
        types.add(DnsRecordType.AFSDB, "AFSDB", new AFSDBRecord());
        types.add(DnsRecordType.X25, "X25", new X25Record());
        types.add(DnsRecordType.ISDN, "ISDN", new ISDNRecord());
        types.add(DnsRecordType.RT, "RT", new RTRecord());
        types.add(DnsRecordType.NSAP, "NSAP", new NSAPRecord());
        types.add(DnsRecordType.NSAP_PTR, "NSAP-PTR", new NSAP_PTRRecord());
        types.add(DnsRecordType.SIG, "SIG", new SIGRecord());
        types.add(DnsRecordType.KEY, "KEY", new KEYRecord());
        types.add(DnsRecordType.PX, "PX", new PXRecord());
        types.add(DnsRecordType.GPOS, "GPOS", new GPOSRecord());
        types.add(DnsRecordType.AAAA, "AAAA", new AAAARecord());
        types.add(DnsRecordType.LOC, "LOC", new LOCRecord());
        types.add(DnsRecordType.NXT, "NXT", new NXTRecord());
        types.add(DnsRecordType.EID, "EID");
        types.add(DnsRecordType.NIMLOC, "NIMLOC");
        types.add(DnsRecordType.SRV, "SRV", new SRVRecord());
        types.add(DnsRecordType.ATMA, "ATMA");
        types.add(DnsRecordType.NAPTR, "NAPTR", new NAPTRRecord());
        types.add(DnsRecordType.KX, "KX", new KXRecord());
        types.add(DnsRecordType.CERT, "CERT", new CERTRecord());
        types.add(DnsRecordType.A6, "A6", new A6Record());
        types.add(DnsRecordType.DNAME, "DNAME", new DNAMERecord());
        types.add(DnsRecordType.OPT, "OPT", new OPTRecord());
        types.add(DnsRecordType.APL, "APL", new APLRecord());
        types.add(DnsRecordType.DS, "DS", new DSRecord());
        types.add(DnsRecordType.SSHFP, "SSHFP", new SSHFPRecord());
        types.add(DnsRecordType.IPSECKEY, "IPSECKEY", new IPSECKEYRecord());
        types.add(DnsRecordType.RRSIG, "RRSIG", new RRSIGRecord());
        types.add(DnsRecordType.NSEC, "NSEC", new NSECRecord());
        types.add(DnsRecordType.DNSKEY, "DNSKEY", new DNSKEYRecord());
        types.add(DnsRecordType.DHCID, "DHCID", new DHCIDRecord());
        types.add(DnsRecordType.NSEC3, "NSEC3", new NSEC3Record());
        types.add(DnsRecordType.NSEC3PARAM, "NSEC3PARAM", new NSEC3PARAMRecord());
        types.add(DnsRecordType.TLSA, "TLSA", new TLSARecord());
        types.add(DnsRecordType.SMIMEA, "SMIMEA", new SMIMEARecord());
        types.add(DnsRecordType.SMIMEA, "HIP");
        types.add(DnsRecordType.OPENPGPKEY, "OPENPGPKEY", new OPENPGPKEYRecord());
        types.add(DnsRecordType.SPF, "SPF", new SPFRecord());
        types.add(DnsRecordType.TKEY, "TKEY", new TKEYRecord());
        types.add(DnsRecordType.TSIG, "TSIG", new TSIGRecord());
        types.add(DnsRecordType.IXFR, "IXFR");
        types.add(DnsRecordType.AXFR, "AXFR");
        types.add(DnsRecordType.MAILB, "MAILB");
        types.add(DnsRecordType.MAILA, "MAILA");
        types.add(DnsRecordType.ANY, "ANY");
        types.add(DnsRecordType.URI, "URI", new URIRecord());
        types.add(DnsRecordType.CAA, "CAA", new CAARecord());
        types.add(DnsRecordType.TA, "TA");
        types.add(DnsRecordType.DLV, "DLV", new DLVRecord());
    }
}
