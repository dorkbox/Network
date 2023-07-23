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
 * Copyright 2014-2020 Real Logic Limited.
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

import kotlinx.coroutines.delay
import org.agrona.concurrent.SleepingMillisIdleStrategy

/**
 * When idle this strategy is to sleep for a specified period time in milliseconds.
 *
 *
 * This class uses [Coroutine.delay] to idle.
 */
class CoroutineSleepingMillisIdleStrategy : CoroutineIdleStrategy {
    companion object {
        /**
         * Name to be returned from [.alias].
         */
        const val ALIAS = "coroutine-sleep-ms"

        /**
         * Default sleep period when the default constructor is used.
         */
        const val DEFAULT_SLEEP_PERIOD_MS = 1L
    }

    private val sleepPeriodMs: Long

    /**
     * Default constructor that uses [.DEFAULT_SLEEP_PERIOD_MS].
     */
    constructor() {
        sleepPeriodMs = DEFAULT_SLEEP_PERIOD_MS
    }

    /**
     * Constructed a new strategy that will sleep for a given period when idle.
     *
     * @param sleepPeriodMs period in milliseconds for which the strategy will sleep when work count is 0.
     */
    constructor(sleepPeriodMs: Long) {
        this.sleepPeriodMs = sleepPeriodMs
    }

    /**
     * {@inheritDoc}
     */
    override suspend fun idle(workCount: Int) {
        if (workCount > 0) {
            return
        }
        delay(sleepPeriodMs)
    }

    /**
     * {@inheritDoc}
     */
    override suspend fun idle() {
        delay(sleepPeriodMs)
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

    /**
     * Creates a clone of this IdleStrategy
     */
    override fun clone(): CoroutineSleepingMillisIdleStrategy {
        return CoroutineSleepingMillisIdleStrategy(sleepPeriodMs = sleepPeriodMs)
    }

    /**
     * Creates a clone of this IdleStrategy
     */
    override fun cloneToNormal(): SleepingMillisIdleStrategy {
        return SleepingMillisIdleStrategy(sleepPeriodMs)
    }


    override fun toString(): String {
        return "CoroutineSleepingMillisIdleStrategy{" +
                "alias=" + ALIAS +
                ", sleepPeriodMs=" + sleepPeriodMs +
                '}'
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CoroutineSleepingMillisIdleStrategy) return false

        if (sleepPeriodMs != other.sleepPeriodMs) return false

        return true
    }

    override fun hashCode(): Int {
        return sleepPeriodMs.hashCode()
    }
}
