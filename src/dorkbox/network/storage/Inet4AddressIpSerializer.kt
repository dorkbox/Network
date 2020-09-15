package dorkbox.network.storage

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import java.net.Inet4Address
import java.net.InetAddress

// NOTE: This only serializes the IP address, not the hostname!
class Inet4AddressIpSerializer : Serializer<Inet4Address>() {
    init {
        isImmutable = true
    }

    override fun write(kryo: Kryo, output: Output, `object`: Inet4Address) {
        output.write(`object`.address, 0, 4)
    }

    override fun read(kryo: Kryo, input: Input, type: Class<out Inet4Address>): Inet4Address {
        return InetAddress.getByAddress("", input.readBytes(4)) as Inet4Address
    }
}
