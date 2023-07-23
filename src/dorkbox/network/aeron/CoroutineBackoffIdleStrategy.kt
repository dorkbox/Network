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
import kotlinx.coroutines.yield
import org.agrona.concurrent.BackoffIdleStrategy
import org.agrona.hints.ThreadHints

abstract class BackoffIdleStrategyPrePad {
    val p000: Byte = 0
    val p001: Byte = 0
    val p002: Byte = 0
    val p003: Byte = 0
    val p004: Byte = 0
    val p005: Byte = 0
    val p006: Byte = 0
    val p007: Byte = 0
    val p008: Byte = 0
    val p009: Byte = 0
    val p010: Byte = 0
    val p011: Byte = 0
    val p012: Byte = 0
    val p013: Byte = 0
    val p014: Byte = 0
    val p015: Byte = 0
    val p016: Byte = 0
    val p017: Byte = 0
    val p018: Byte = 0
    val p019: Byte = 0
    val p020: Byte = 0
    val p021: Byte = 0
    val p022: Byte = 0
    val p023: Byte = 0
    val p024: Byte = 0
    val p025: Byte = 0
    val p026: Byte = 0
    val p027: Byte = 0
    val p028: Byte = 0
    val p029: Byte = 0
    val p030: Byte = 0
    val p031: Byte = 0
    val p032: Byte = 0
    val p033: Byte = 0
    val p034: Byte = 0
    val p035: Byte = 0
    val p036: Byte = 0
    val p037: Byte = 0
    val p038: Byte = 0
    val p039: Byte = 0
    val p040: Byte = 0
    val p041: Byte = 0
    val p042: Byte = 0
    val p043: Byte = 0
    val p044: Byte = 0
    val p045: Byte = 0
    val p046: Byte = 0
    val p047: Byte = 0
    val p048: Byte = 0
    val p049: Byte = 0
    val p050: Byte = 0
    val p051: Byte = 0
    val p052: Byte = 0
    val p053: Byte = 0
    val p054: Byte = 0
    val p055: Byte = 0
    val p056: Byte = 0
    val p057: Byte = 0
    val p058: Byte = 0
    val p059: Byte = 0
    val p060: Byte = 0
    val p061: Byte = 0
    val p062: Byte = 0
    val p063: Byte = 0
}

abstract class BackoffIdleStrategyData(
        protected val maxSpins: Long, protected val maxYields: Long, protected val minParkPeriodMs: Long, protected val maxParkPeriodMs: Long) : BackoffIdleStrategyPrePad() {

    protected var state = 0 // NOT_IDLE
    protected var spins: Long = 0
    protected var yields: Long = 0
    protected var parkPeriodMs: Long = 0
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BackoffIdleStrategyData) return false

        if (maxSpins != other.maxSpins) return false
        if (maxYields != other.maxYields) return false
        if (minParkPeriodMs != other.minParkPeriodMs) return false
        if (maxParkPeriodMs != other.maxParkPeriodMs) return false
        if (state != other.state) return false
        if (spins != other.spins) return false
        if (yields != other.yields) return false
        if (parkPeriodMs != other.parkPeriodMs) return false

        return true
    }

    override fun hashCode(): Int {
        var result = maxSpins.hashCode()
        result = 31 * result + maxYields.hashCode()
        result = 31 * result + minParkPeriodMs.hashCode()
        result = 31 * result + maxParkPeriodMs.hashCode()
        result = 31 * result + state
        result = 31 * result + spins.hashCode()
        result = 31 * result + yields.hashCode()
        result = 31 * result + parkPeriodMs.hashCode()
        return result
    }
}

/**
 * Idling strategy for threads when they have no work to do.
 * <p>
 * Spin for maxSpins, then
 * [Coroutine.yield] for maxYields, then
 * [Coroutine.delay] on an exponential backoff to maxParkPeriodMs
 */
@Suppress("unused")
class CoroutineBackoffIdleStrategy : BackoffIdleStrategyData, CoroutineIdleStrategy {
    val p064: Byte = 0
    val p065: Byte = 0
    val p066: Byte = 0
    val p067: Byte = 0
    val p068: Byte = 0
    val p069: Byte = 0
    val p070: Byte = 0
    val p071: Byte = 0
    val p072: Byte = 0
    val p073: Byte = 0
    val p074: Byte = 0
    val p075: Byte = 0
    val p076: Byte = 0
    val p077: Byte = 0
    val p078: Byte = 0
    val p079: Byte = 0
    val p080: Byte = 0
    val p081: Byte = 0
    val p082: Byte = 0
    val p083: Byte = 0
    val p084: Byte = 0
    val p085: Byte = 0
    val p086: Byte = 0
    val p087: Byte = 0
    val p088: Byte = 0
    val p089: Byte = 0
    val p090: Byte = 0
    val p091: Byte = 0
    val p092: Byte = 0
    val p093: Byte = 0
    val p094: Byte = 0
    val p095: Byte = 0
    val p096: Byte = 0
    val p097: Byte = 0
    val p098: Byte = 0
    val p099: Byte = 0
    val p100: Byte = 0
    val p101: Byte = 0
    val p102: Byte = 0
    val p103: Byte = 0
    val p104: Byte = 0
    val p105: Byte = 0
    val p106: Byte = 0
    val p107: Byte = 0
    val p108: Byte = 0
    val p109: Byte = 0
    val p110: Byte = 0
    val p111: Byte = 0
    val p112: Byte = 0
    val p113: Byte = 0
    val p114: Byte = 0
    val p115: Byte = 0
    val p116: Byte = 0
    val p117: Byte = 0
    val p118: Byte = 0
    val p119: Byte = 0
    val p120: Byte = 0
    val p121: Byte = 0
    val p122: Byte = 0
    val p123: Byte = 0
    val p124: Byte = 0
    val p125: Byte = 0
    val p126: Byte = 0
    val p127: Byte = 0

    companion object {
        private const val NOT_IDLE = 0
        private const val SPINNING = 1
        private const val YIELDING = 2
        private const val PARKING = 3

        /**
         * Name to be returned from [.alias].
         */
        const val ALIAS = "coroutine-backoff"

        /**
         * Default number of times the strategy will spin without work before going to next state.
         */
        const val DEFAULT_MAX_SPINS = 1000L

        /**
         * Default number of times the strategy will yield without work before going to next state.
         */
        const val DEFAULT_MAX_YIELDS = 1000L

        /**
         * Default interval the strategy will park the thread on entering the park state in milliseconds.
         */
        const val DEFAULT_MIN_PARK_PERIOD_MS = 1L

        /**
         * Default interval the strategy will park the thread will expand interval to as a max in milliseconds.
         */
        const val DEFAULT_MAX_PARK_PERIOD_MS = 4L
    }

    /**
     * Default constructor using [.DEFAULT_MAX_SPINS], [.DEFAULT_MAX_YIELDS], [.DEFAULT_MIN_PARK_PERIOD_MS], and [.DEFAULT_MAX_PARK_PERIOD_MS].
     */
    constructor() : super(DEFAULT_MAX_SPINS, DEFAULT_MAX_YIELDS, DEFAULT_MIN_PARK_PERIOD_MS, DEFAULT_MAX_PARK_PERIOD_MS)

    /**
     * Create a set of state tracking idle behavior
     * <p>
     * @param maxSpins        to perform before moving to [Coroutine.yield]
     * @param maxYields       to perform before moving to [Coroutine.delay]
     * @param minParkPeriodMs to use when initiating parking
     * @param maxParkPeriodMs to use for end duration when parking
     */
    constructor(maxSpins: Long, maxYields: Long, minParkPeriodMs: Long, maxParkPeriodMs: Long)
            : super(maxSpins, maxYields, minParkPeriodMs, maxParkPeriodMs) {
    }

    /**
     * Perform current idle action (e.g. nothing/yield/sleep). This method signature expects users to call into it on
     * every work 'cycle'. The implementations may use the indication "workCount &gt; 0" to reset internal backoff
     * state. This method works well with 'work' APIs which follow the following rules:
     * <ul>
     * <li>'work' returns a value larger than 0 when some work has been done</li>
     * <li>'work' returns 0 when no work has been done</li>
     * <li>'work' may return error codes which are less than 0, but which amount to no work has been done</li>
     * </ul>
     * <p>
     * Callers are expected to follow this pattern:
     *
     * <pre>
     * <code>
     * while (isRunning)
     * {
     *     idleStrategy.idle(doWork());
     * }
     * </code>
     * </pre>
     *
     * @param workCount performed in last duty cycle.
     */
    override suspend fun idle(workCount: Int) {
        if (workCount > 0) {
            reset()
        } else {
            idle()
        }
    }

    /**
     * Perform current idle action (e.g. nothing/yield/sleep). To be used in conjunction with
     * {@link IdleStrategy#reset()} to clear internal state when idle period is over (or before it begins).
     * Callers are expected to follow this pattern:
     *
     * <pre>
     * <code>
     * while (isRunning)
     * {
     *   if (!hasWork())
     *   {
     *     idleStrategy.reset();
     *     while (!hasWork())
     *     {
     *       if (!isRunning)
     *       {
     *         return;
     *       }
     *       idleStrategy.idle();
     *     }
     *   }
     *   doWork();
     * }
     * </code>
     * </pre>
     */
    override suspend fun idle() {
        when (state) {
            NOT_IDLE -> {
                state = SPINNING
                spins++
            }

            SPINNING -> {
                ThreadHints.onSpinWait()
                if (++spins > maxSpins) {
                    state = YIELDING
                    yields = 0
                }
            }

            YIELDING -> if (++yields > maxYields) {
                state = PARKING
                parkPeriodMs = minParkPeriodMs
            } else {
                yield()
            }

            PARKING -> {
                delay(parkPeriodMs)
                // double the delay until we get to MAX
                parkPeriodMs = (parkPeriodMs shl 1).coerceAtMost(maxParkPeriodMs)
            }
        }
    }


    /**
     * Reset the internal state in preparation for entering an idle state again.
     */
    override fun reset() {
        spins = 0
        yields = 0
        parkPeriodMs = minParkPeriodMs
        state = NOT_IDLE
    }

    /**
     * Simple name by which the strategy can be identified.
     *
     * @return simple name by which the strategy can be identified.
     */
    override fun alias(): String {
        return ALIAS
    }

    /**
     * Creates a clone of this IdleStrategy
     */
    override fun clone(): CoroutineBackoffIdleStrategy {
        return CoroutineBackoffIdleStrategy(maxSpins = maxSpins, maxYields = maxYields, minParkPeriodMs = minParkPeriodMs, maxParkPeriodMs = maxParkPeriodMs)
    }

    /**
     * Creates a clone of this IdleStrategy
     */
    override fun cloneToNormal(): BackoffIdleStrategy {
        return BackoffIdleStrategy(maxSpins, maxYields, minParkPeriodMs, maxParkPeriodMs)
    }

    override fun toString(): String {
        return "CoroutineBackoffIdleStrategy{" +
                "alias=" + ALIAS +
                ", maxSpins=" + maxSpins +
                ", maxYields=" + maxYields +
                ", minParkPeriodMs=" + minParkPeriodMs +
                ", maxParkPeriodMs=" + maxParkPeriodMs +
                '}'
    }
}
