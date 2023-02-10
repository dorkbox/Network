/*
 * Copyright 2020 dorkbox, llc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkbox.network.handshake

import dorkbox.network.exceptions.AllocationException
import dorkbox.objectPool.ObjectPool
import dorkbox.objectPool.Pool
import kotlinx.atomicfu.atomic
import java.security.SecureRandom

/**
 * An allocator for random IDs, the maximum number of IDs is an unsigned short (65535).
 *
 * The allocator randomly selects values from the given range `[min, max]` and will not return a previously-returned value `x`
 * until `x` has been freed with [free].
 *
 * This implementation uses storage of {max-min}, with a maximum amount of 65534.
 *
 * @param min The minimum ID (inclusive)
 * @param max The maximum ID (exclusive)
 */
class RandomId65kAllocator(private val min: Int, max: Int) {

    constructor(size: Int): this(1, size + 1)


    private val cache: Pool<Int>
    private val maxAssignments: Int
    private val assigned = atomic(0)


    init {
        // IllegalArgumentException
        require(max >= min) {
            "Maximum value $max must be >= minimum value $min"
        }

        val max65k = Short.MAX_VALUE * 2
        maxAssignments = (max - min).coerceIn(1, max65k)

        // create a shuffled list of ID's. This operation is ONLY performed ONE TIME per endpoint!
        val ids = ArrayList<Int>(maxAssignments)
        for (id in min until min + maxAssignments) {
            ids.add(id)
        }

        ids.shuffle(SecureRandom())

        // populate the array of randomly assigned ID's.
        cache = ObjectPool.blocking(ids)
    }

    /**
     * Allocate an unused ID. Will never allocate ID '0'
     *
     * @return A new, unused ID
     *
     * @throws AllocationException If there are no non-allocated IDs left
     */
    fun allocate(): Int {
        if (assigned.value == maxAssignments) {
            throw AllocationException("No IDs left to allocate")
        }

        assigned.getAndIncrement()
        return cache.take()
    }

    /**
     * Free an ID for use later. The id is returned to the cache for usage later
     *
     * @param id The ID to free
     */
    fun free(id: Int) {
        val assigned = assigned.decrementAndGet()
        if (assigned < 0) {
            throw AllocationException("Unequal allocate/free method calls.")
        }
        cache.put(id)
    }

    fun isEmpty(): Boolean {
        return assigned.value == 0
    }

    override fun toString(): String {
        return "$assigned"
    }
}
