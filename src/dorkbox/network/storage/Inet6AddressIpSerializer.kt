package dorkbox.network.storage

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.KryoException
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

// NOTE: This only serializes the IP address, not the hostname!
class Inet6AddressIpSerializer : Serializer<Inet6Address>() {
    init {
        isImmutable = true
    }

    override fun write(kryo: Kryo, output: Output, `object`: Inet6Address) {
        output.write(`object`.address, 0, 16)
    }

    override fun read(kryo: Kryo, input: Input, type: Class<out Inet6Address>): Inet6Address {
        return try {
            InetAddress.getByAddress("", input.readBytes(16)) as Inet6Address
        } catch (e: UnknownHostException) {
            throw KryoException(e)
        }
    }
}
