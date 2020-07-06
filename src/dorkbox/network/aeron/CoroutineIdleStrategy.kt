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

/**
 * Idle strategy for use by threads when they do not have work to do.
 *
 *
 * **Note regarding implementor state**
 *
 *
 * Some implementations are known to be stateful, please note that you cannot safely assume implementations to be
 * stateless. Where implementations are stateful it is recommended that implementation state is padded to avoid false
 * sharing.
 *
 *
 * **Note regarding potential for TTSP(Time To Safe Point) issues**
 *
 *
 * If the caller spins in a 'counted' loop, and the implementation does not include a a safepoint poll this may cause a
 * TTSP (Time To SafePoint) problem. If this is the case for your application you can solve it by preventing the idle
 * method from being inlined by using a Hotspot compiler command as a JVM argument e.g:
 * `-XX:CompileCommand=dontinline,org.agrona.concurrent.NoOpIdleStrategy::idle`
 */
interface CoroutineIdleStrategy {
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
    suspend fun idle(workCount: Int)

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
    suspend fun idle()

    /**
     * Reset the internal state in preparation for entering an idle state again.
     */
    fun reset()

    /**
     * Simple name by which the strategy can be identified.
     *
     * @return simple name by which the strategy can be identified.
     */
    fun alias(): String {
        return ""
    }

    /**
     * Creates a clone of this IdleStrategy
     */
    fun clone(): CoroutineIdleStrategy
}
