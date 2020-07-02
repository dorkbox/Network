package dorkbox.network.aeron.server

import org.agrona.collections.IntArrayList

/**
 * An allocator for port numbers.
 *
 * The allocator accepts a base number `p` and a maximum count `n | n > 0`, and will allocate
 * up to `n` numbers, in a random order, in the range `[p, p + n - 1`.
 *
 * @param basePort The base port
 * @param numberOfPortsToAllocate The maximum number of ports that will be allocated
 *
 * @throws IllegalArgumentException If the port range is not valid
 */
class PortAllocator(basePort: Int, numberOfPortsToAllocate: Int) {
    private val minPort: Int
    private val maxPort: Int

    private val portShuffleReset: Int
    private var portShuffleCount: Int
    private val freePorts: IntArrayList

    init {
        if (basePort !in 1..65535) {
            throw IllegalArgumentException("Base port $basePort must be in the range [1, 65535]")
        }

        minPort = basePort
        maxPort = basePort + (numberOfPortsToAllocate - 1)

        if (maxPort !in (basePort + 1)..65535) {
            throw IllegalArgumentException("Uppermost port $maxPort must be in the range [$basePort, 65535]")
        }

        // every time we add 25% of ports back (via 'free'), reshuffle the ports
        portShuffleReset = numberOfPortsToAllocate/4
        portShuffleCount = portShuffleReset

        freePorts = IntArrayList()

        for (port in basePort..maxPort) {
            freePorts.addInt(port)
        }

        freePorts.shuffle()
    }

    /**
     * Allocate `count` number of ports.
     *
     * @param count The number of ports that will be allocated
     *
     * @return An array of allocated ports
     *
     * @throws PortAllocationException If there are fewer than `count` ports available to allocate
     */
    @Throws(IllegalArgumentException::class)
    fun allocate(count: Int): IntArray {
        if (freePorts.size < count) {
            throw IllegalArgumentException("Too few ports available to allocate $count ports")
        }

        // reshuffle the ports once we need to re-allocate a new port
        if (portShuffleCount <= 0) {
            portShuffleCount = portShuffleReset
            freePorts.shuffle()
        }

        val result = IntArray(count)
        for (index in 0 until count) {
            val lastValue = freePorts.size - 1
            val removed = freePorts.removeAt(lastValue)
            result[index] = removed
        }

        return result
    }

    /**
     * Frees the given ports. Has no effect if the given port is outside of the range considered by the allocator.
     *
     * @param ports The array of ports to free
     */
    fun free(ports: IntArray) {
        ports.forEach {
            free(it)
        }
    }

    /**
     * Free a given port.
     * <p>
     * Has no effect if the given port is outside of the range considered by the allocator.
     *
     * @param port The port
     */
    fun free(port: Int) {
        if (port in minPort..maxPort) {
            // add at the end (so we don't have unnecessary array resizes)
            freePorts.addInt(freePorts.size, port)

            portShuffleCount--
        }
    }
}
