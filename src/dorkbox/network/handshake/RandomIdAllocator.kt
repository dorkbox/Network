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
import org.agrona.collections.IntHashSet
import java.security.SecureRandom

/**
 * An allocator for random IDs.
 *
 * The allocator randomly selects values from the given range `[min, max]` and will not return a previously-returned value `x`
 * until `x` has been freed with [free].
 *
 * This implementation uses storage proportional to the number of currently-allocated
 * values. Allocation time is bounded by { max - min}, will be { O(1)}
 * with no allocated values, and will increase to { O(n)} as the number
 * of allocated values approached { max - min}.
 *
 * NOTE: THIS IS NOT THREAD SAFE!
 *
 * @param min The minimum ID (inclusive)
 * @param max The maximum ID (exclusive)
 */
class RandomIdAllocator(private val min: Int = Integer.MIN_VALUE, max: Int = Integer.MAX_VALUE) {
    private val used = IntHashSet()
    private val random = SecureRandom()
    private val maxAssignments: Int

    init {
        // IllegalArgumentException
        require(max >= min) {
            "Maximum value $max must be >= minimum value $min"
        }

        maxAssignments = (max - min).coerceAtLeast(1)
    }

    /**
     * Allocate an unused ID. Will never allocate ID '0'
     *
     * @return A new, unused ID
     *
     * @throws AllocationException If there are no non-allocated IDs left
     */
    fun allocate(): Int {
        if (used.size == maxAssignments) {
            throw AllocationException("No IDs left to allocate")
        }

        for (index in 0 until maxAssignments) {
            val session = random.nextInt(maxAssignments) + min
            if (!used.contains(session)) {
                used.add(session)
                return session
            }
        }

        throw AllocationException("Unable to allocate a ID after $maxAssignments attempts (${used.size} values in use")
    }

    /**
     * Free an ID for use later. After this method returns, the ID becomes eligible for allocation by future calls to [allocate].
     *
     * @param id The ID to free
     */
    fun free(id: Int) {
        used.remove(id)
    }


    /**
     * Removes all used IDs from the internal data structures
     */
    fun clear() {
        used.clear()
    }
}
