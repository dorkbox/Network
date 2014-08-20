
package dorkbox.network.connection.registration;

import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.params.IESParameters;


/**
 * Internal message to handle the TCP/UDP registration process
 */
public class Registration {
    public ECPublicKeyParameters publicKey;
    public IESParameters eccParameters;
    public byte[] aesKey;
    public byte[] aesIV;

    public byte[] payload;
}
