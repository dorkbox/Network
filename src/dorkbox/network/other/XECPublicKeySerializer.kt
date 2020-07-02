package dorkbox.network.other

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import java.security.KeyFactory
import java.security.interfaces.XECPublicKey
import java.security.spec.X509EncodedKeySpec

/**
 * Only public keys are ever sent across the wire.
 */
class XECPublicKeySerializer : Serializer<XECPublicKey>() {
    private val keyFactory = KeyFactory.getInstance("EC")

    override fun write(kryo: Kryo, output: Output, publicKey: XECPublicKey) {
        val encoded = publicKey.encoded
        output.writeInt(encoded.size, true)
        output.write(encoded)
    }

    override fun read(kryo: Kryo, input: Input, type: Class<out XECPublicKey>): XECPublicKey {
        val length = input.readInt(true)
        val encoded = ByteArray(length)
        return keyFactory.generatePublic(X509EncodedKeySpec(encoded)) as XECPublicKey
    }
}
