package dorkbox.network.handshake

import org.agrona.collections.Object2IntHashMap
import java.net.InetAddress

/**
 *
 */
internal class ConnectionCounts {
    private val connectionsPerIpCounts = Object2IntHashMap<InetAddress>(-1)

    fun get(inetAddress: InetAddress): Int {
        return connectionsPerIpCounts.getOrPut(inetAddress) { 0 }
    }

    fun increment(inetAddress: InetAddress, currentCount: Int) {
        connectionsPerIpCounts[inetAddress] = currentCount + 1
    }

    fun decrement(inetAddress: InetAddress, currentCount: Int) {
        connectionsPerIpCounts[inetAddress] = currentCount - 1
    }

    fun decrementSlow(inetAddress: InetAddress) {
        if (connectionsPerIpCounts.containsKey(inetAddress)) {
            val defaultVal = connectionsPerIpCounts.getValue(inetAddress)
            connectionsPerIpCounts[inetAddress] = defaultVal - 1
        }
    }

    fun isEmpty(): Boolean {
        return connectionsPerIpCounts.isEmpty()
    }
}
