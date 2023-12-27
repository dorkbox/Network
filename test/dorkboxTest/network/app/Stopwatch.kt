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
 * Copyright (C) 2008 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package dorkboxTest.network.app

import java.time.Duration
import java.util.concurrent.*

/**
 * An object that measures elapsed time in nanoseconds. It is useful to measure elapsed time using
 * this class instead of direct calls to [System.nanoTime] for a few reasons:
 *
 *
 *  * An alternate time source can be substituted, for testing or performance reasons.
 *  * As documented by `nanoTime`, the value returned has no absolute meaning, and can only
 * be interpreted as relative to another timestamp returned by `nanoTime` at a different
 * time. `Stopwatch` is a more effective abstraction because it exposes only these
 * relative values, not the absolute ones.
 *
 *
 *
 * Basic usage:
 *
 * <pre>`Stopwatch stopwatch = Stopwatch.createStarted();
 * doSomething();
 * stopwatch.stop(); // optional
 *
 * Duration duration = stopwatch.elapsed();
 *
 * log.info("time: " + stopwatch); // formatted string like "12.3 ms"
`</pre> *
 *
 *
 * Stopwatch methods are not idempotent; it is an error to start or stop a stopwatch that is
 * already in the desired state.
 *
 *
 * When testing code that uses this class, use [.createUnstarted] or [ ][.createStarted] to supply a fake or mock ticker. This allows you to simulate any valid
 * behavior of the stopwatch.
 *
 *
 * **Note:** This class is not thread-safe.
 *
 *
 * **Warning for Android users:** a stopwatch with default behavior may not continue to keep
 * time while the device is asleep. Instead, create one like this:
 *
 * <pre>`Stopwatch.createStarted(
 * new Ticker() {
 * public long read() {
 * return android.os.SystemClock.elapsedRealtimeNanos();
 * }
 * });
`</pre> *
 *
 * @author Kevin Bourrillion
 * @since 10.0
 */
class Stopwatch {
    private val ticker: Ticker

    /**
     * Returns `true` if [.start] has been called on this stopwatch, and [.stop]
     * has not been called since the last call to `start()`.
     */
    var isRunning = false
        private set
    private var elapsedNanos: Long = 0
    private var startTick: Long = 0

    internal constructor() {
        ticker = Ticker.systemTicker()
    }

    internal constructor(ticker: Ticker?) {
        if (ticker == null) {
            throw NullPointerException("ticker")
        }
        this.ticker = ticker
    }

    /**
     * Starts the stopwatch.
     *
     * @return this `Stopwatch` instance
     *
     * @throws IllegalStateException if the stopwatch is already running.
     */
    fun start(): Stopwatch {
        check(!isRunning) { "This stopwatch is already running." }
        isRunning = true
        startTick = ticker.read()
        return this
    }

    /**
     * Stops the stopwatch. Future reads will return the fixed duration that had elapsed up to this
     * point.
     *
     * @return this `Stopwatch` instance
     *
     * @throws IllegalStateException if the stopwatch is already stopped.
     */
    fun stop(): Stopwatch {
        val tick = ticker.read()
        check(isRunning) { "This stopwatch is already stopped." }
        isRunning = false
        elapsedNanos += tick - startTick
        return this
    }

    /**
     * Sets the elapsed time for this stopwatch to zero, and places it in a stopped state.
     *
     * @return this `Stopwatch` instance
     */
    fun reset(): Stopwatch {
        elapsedNanos = 0
        isRunning = false
        return this
    }

    fun elapsedNanos(): Long {
        return if (isRunning) ticker.read() - startTick + elapsedNanos else elapsedNanos
    }

    /**
     * Returns the current elapsed time shown on this stopwatch, expressed in the desired time unit,
     * with any fraction rounded down.
     *
     *
     * **Note:** the overhead of measurement can be more than a microsecond, so it is generally
     * not useful to specify [TimeUnit.NANOSECONDS] precision here.
     *
     *
     * It is generally not a good idea to use an ambiguous, unitless `long` to represent
     * elapsed time. Therefore, we recommend using [.elapsed] instead, which returns a
     * strongly-typed [Duration] instance.
     *
     * @since 14.0 (since 10.0 as `elapsedTime()`)
     */
    fun elapsed(desiredUnit: TimeUnit): Long {
        return desiredUnit.convert(elapsedNanos(), TimeUnit.NANOSECONDS)
    }

    /**
     * Returns the current elapsed time shown on this stopwatch as a [Duration]. Unlike [ ][.elapsed], this method does not lose any precision due to rounding.
     *
     * @since 22.0
     */
    fun elapsed(): Duration {
        return Duration.ofNanos(elapsedNanos())
    }

    /**
     * Returns a string representation of the current elapsed time.
     */
    override fun toString(): String {
        return toString(elapsedNanos())
    }

    companion object {
        /**
         * Creates (but does not start) a new stopwatch using [System.nanoTime] as its time source.
         *
         * @since 15.0
         */
        fun createUnstarted(): Stopwatch {
            return Stopwatch()
        }

        /**
         * Creates (but does not start) a new stopwatch, using the specified time source.
         *
         * @since 15.0
         */
        fun createUnstarted(ticker: Ticker?): Stopwatch {
            return Stopwatch(ticker)
        }

        /**
         * Creates (and starts) a new stopwatch using [System.nanoTime] as its time source.
         *
         * @since 15.0
         */
        fun createStarted(): Stopwatch {
            return Stopwatch().start()
        }

        /**
         * Creates (and starts) a new stopwatch, using the specified time source.
         *
         * @since 15.0
         */
        fun createStarted(ticker: Ticker?): Stopwatch {
            return Stopwatch(ticker).start()
        }

        fun toString(nanos: Long): String {
            val unit = chooseUnit(nanos)
            val value = nanos.toDouble() / TimeUnit.NANOSECONDS.convert(1, unit)

            // Too bad this functionality is not exposed as a regular method call
            return String.format("%.4g %s", value, abbreviate(unit))
        }

        fun chooseUnit(nanos: Long): TimeUnit {
            if (TimeUnit.DAYS.convert(nanos, TimeUnit.NANOSECONDS) > 0) {
                return TimeUnit.DAYS
            }
            if (TimeUnit.HOURS.convert(nanos, TimeUnit.NANOSECONDS) > 0) {
                return TimeUnit.HOURS
            }
            if (TimeUnit.MINUTES.convert(nanos, TimeUnit.NANOSECONDS) > 0) {
                return TimeUnit.MINUTES
            }
            if (TimeUnit.SECONDS.convert(nanos, TimeUnit.NANOSECONDS) > 0) {
                return TimeUnit.SECONDS
            }
            if (TimeUnit.MILLISECONDS.convert(nanos, TimeUnit.NANOSECONDS) > 0) {
                return TimeUnit.MILLISECONDS
            }
            return if (TimeUnit.MICROSECONDS.convert(nanos, TimeUnit.NANOSECONDS) > 0) {
                TimeUnit.MICROSECONDS
            } else TimeUnit.NANOSECONDS
        }

        private fun abbreviate(unit: TimeUnit): String {
            return when (unit) {
                TimeUnit.NANOSECONDS -> "ns"
                TimeUnit.MICROSECONDS -> "\u03bcs" // μs
                TimeUnit.MILLISECONDS -> "ms"
                TimeUnit.SECONDS -> "s"
                TimeUnit.MINUTES -> "min"
                TimeUnit.HOURS -> "h"
                TimeUnit.DAYS -> "d"
                else -> throw AssertionError()
            }
        }
    }
}
