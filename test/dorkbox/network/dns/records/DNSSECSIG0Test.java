package dorkbox.network.dns.records;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;

import dorkbox.network.dns.Name;
import dorkbox.network.dns.constants.DnsClass;
import junit.framework.TestCase;

public
class DNSSECSIG0Test extends TestCase {

    private static final String SIGNATURE_ALGORITHM = "SHA1withRSA";
    private static final String KEY_ALGORITHM = "RSA";
    int algorithm = DNSSEC.Algorithm.RSASHA1;
    byte[] toSign = "The quick brown fox jumped over the lazy dog.".getBytes();

    @Override
    public
    void setUp() {
    }

    @Override
    public
    void tearDown() {
    }

    public
    void testSIG0() throws Exception {
        Name sig0zoneName = new Name("sig0.invalid.");
        Name sig0hostName = new Name("sometext.sig0.invalid.");

        KeyPairGenerator rsagen = KeyPairGenerator.getInstance("RSA");
        KeyPair rsapair = rsagen.generateKeyPair();
        PrivateKey privKey = rsapair.getPrivate();
        PublicKey pubKey = rsapair.getPublic();

        KEYRecord keyRecord = new KEYRecord(sig0zoneName,
                                            DnsClass.IN,
                                            0,
                                            KEYRecord.Flags.HOST,
                                            KEYRecord.Protocol.DNSSEC,
                                            DNSSEC.Algorithm.RSASHA1,
                                            pubKey);
        TXTRecord txtRecord = new TXTRecord(sig0hostName, DnsClass.IN, 0, "Hello World!");
        Update updateMessage = new Update(sig0zoneName);
        updateMessage.add(txtRecord);

        SIG0.signMessage(updateMessage, keyRecord, privKey, null);
        DnsMessage message = new DnsMessage(updateMessage.toWire());
        SIG0.verifyMessage(message, message.toWire(), keyRecord, null);
    }
}
