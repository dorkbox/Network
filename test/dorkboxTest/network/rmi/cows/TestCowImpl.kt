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
package dorkboxTest.network.rmi.cows

import dorkbox.network.connection.Connection
import kotlinx.coroutines.delay

open class TestCowImpl(val id: Int) : TestCowBaseImpl(), TestCow {

    private var moos = 0

    override fun moo() {
        throw RuntimeException("Should never be executed!")
    }

    fun moo(connection: Connection) {
        moos++
        connection.logger.error("Moo! $moos")
    }

    override fun moo(value: String) {
        throw RuntimeException("Should never be executed!")
    }

    override fun mooTwo(value: String): String {
        println(value)
        return "moo-two: $value"
    }

    fun moo(connection: Connection, value: String) {
        moos += 2
        connection.logger.error("Moo! $moos: $value")
    }

    override suspend fun moo(value: String, delay: Long) {
        throw RuntimeException("Should never be executed!")
    }

    suspend fun moo(connection: Connection, value: String, delay: Long) {
        moos += 4
        connection.logger.error("Moo! $moos: $value ($delay)")
        delay(delay)
    }

    override fun id(): Int {
        return id
    }

    override suspend fun slow(): Float {
        throw RuntimeException("Should never be executed!")
    }

    suspend fun slow(connection: Connection): Float {
        connection.logger.error("Slowdown!!")
        delay(2000)
        return 123.0f
    }

    override suspend fun withSuspend(value: String, v2: Int) {
        throw RuntimeException("'$value : $v2' should never be executed!")
    }

    suspend fun withSuspend(connection: Connection, value: String, v2: Int) {
        connection.logger.error("Suspending '$value : $v2'!")
        delay(2000)
    }

    override suspend fun withSuspendAndReturn(value: String, v2: Int): Int {
        throw RuntimeException("'$value : $v2' should never be executed!")
    }

    suspend fun withSuspendAndReturn(connection: Connection, value: String, v2: Int): Int {
        connection.logger.error("Suspending '$value' with return!")
        delay(2000)
        return v2
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other == null || javaClass != other.javaClass) {
            return false
        }
        val that = other as TestCowImpl
        return id == that.id
    }

    override fun hashCode(): Int {
        return id
    }

    override fun toString(): String {
        return "Tada! This is a remote object!"
    }
}
