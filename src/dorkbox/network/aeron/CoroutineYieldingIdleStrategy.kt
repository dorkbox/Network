/*
 * Copyright 2023 dorkbox, llc
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

/*
 * Copyright 2014-2023 Real Logic Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkbox.network.aeron

import org.agrona.concurrent.IdleStrategy
import org.agrona.concurrent.YieldingIdleStrategy

/**
 * [IdleStrategy] that will call [Thread.yield] when the work count is zero.
 */
class CoroutineYieldingIdleStrategy : CoroutineIdleStrategy {
    companion object {
        /**
         * Name to be returned from [.alias].
         */
        const val ALIAS = "coroutine-yield"

        /**
         * As there is no instance state then this object can be used to save on allocation.
         */
        val INSTANCE = CoroutineYieldingIdleStrategy()
    }

    /**
     * {@inheritDoc}
     */
    override suspend fun idle(workCount: Int) {
        if (workCount > 0) {
            return
        }
        Thread.yield()
    }

    /**
     * {@inheritDoc}
     */
    override suspend fun idle() {
        Thread.yield()
    }

    /**
     * {@inheritDoc}
     */
    override fun reset() {}

    /**
     * {@inheritDoc}
     */
    override fun alias(): String {
        return ALIAS
    }

    override fun clone(): CoroutineIdleStrategy {
        return CoroutineYieldingIdleStrategy()
    }

    override fun cloneToNormal(): IdleStrategy {
        return YieldingIdleStrategy()
    }

    /**
     * {@inheritDoc}
     */
    override fun toString(): String {
        return "CoroutineYieldingIdleStrategy{alias=" + ALIAS + "}"
    }
}
