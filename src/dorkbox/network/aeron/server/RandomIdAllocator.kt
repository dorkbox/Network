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
package dorkbox.network.aeron.server

import org.agrona.collections.IntHashSet
import java.security.SecureRandom

/**
 * An allocator for session IDs. The allocator randomly selects values from
 * the given range `[min, max]` and will not return a previously-returned value `x`
 * until `x` has been freed with `{ SessionAllocator#free(int)}.
 * </p>
 *
 * <p>
 * This implementation uses storage proportional to the number of currently-allocated
 * values. Allocation time is bounded by { max - min}, will be { O(1)}
 * with no allocated values, and will increase to { O(n)} as the number
 * of allocated values approached { max - min}.
 * </p>`
 *
 * @param min The minimum session ID (inclusive)
 * @param max The maximum session ID (exclusive)
 */
class RandomIdAllocator(private val min: Int, max: Int) {
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
     * Allocate a new session. Will never allocate session ID '0'
     *
     * @return A new session ID
     *
     * @throws AllocationException If there are no non-allocated sessions left
     */
    @Throws(AllocationException::class)
    fun allocate(): Int {
        if (used.size == maxAssignments) {
            throw AllocationException("No session IDs left to allocate")
        }

        for (index in 0 until maxAssignments) {
            val session = random.nextInt(maxAssignments) + min
            if (!used.contains(session)) {
                used.add(session)
                return session
            }
        }

        throw AllocationException("Unable to allocate a session ID after $maxAssignments attempts (${used.size} values in use")
    }

    /**
     * Free a session. After this method returns, `session` becomes eligible
     * for allocation by future calls to [.allocate].
     *
     * @param session The session to free
     */
    fun free(session: Int) {
        used.remove(session)
    }
}
