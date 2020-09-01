package dorkbox.network.serialization

import org.agrona.collections.Int2ObjectHashMap
import org.agrona.collections.Object2IntHashMap
import org.objenesis.instantiator.ObjectInstantiator

/// RMI things
class RmiHolder {
    val idToInstantiator : Int2ObjectHashMap<ObjectInstantiator<Any>> = Int2ObjectHashMap()

    // note: ONLY RegisterRmi (iface+impl) will have their impl class info saved!

    val ifaceToId = Object2IntHashMap<Class<*>>(Serialization.INVALID_KRYO_ID)
    val implToId = Object2IntHashMap<Class<*>>(Serialization.INVALID_KRYO_ID)

    val idToImpl = Int2ObjectHashMap<Class<*>>()
    val idToIface = Int2ObjectHashMap<Class<*>>()
}
