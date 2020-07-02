package dorkbox.network.other

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import java.security.KeyFactory
import java.security.interfaces.XECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec

/**
 * Only public keys are ever sent across the wire.
 */
class XECPrivateKeySerializer : Serializer<XECPrivateKey>() {
    private val keyFactory = KeyFactory.getInstance("EC")

    override fun write(kryo: Kryo, output: Output, privateKey: XECPrivateKey) {
        val encoded = privateKey.encoded
        output.writeInt(encoded.size, true)
        output.write(encoded)
    }

    override fun read(kryo: Kryo, input: Input, type: Class<out XECPrivateKey>): XECPrivateKey {
        val length = input.readInt(true)
        val encoded = ByteArray(length)
        return keyFactory.generatePrivate(PKCS8EncodedKeySpec(encoded)) as XECPrivateKey
    }
}
