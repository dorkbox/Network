/*
 * Copyright 2019 dorkbox, llc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkboxTest.network.rmi.classes

class TestCowImpl(val id: Int) : TestCowBaseImpl(), TestCow {

    private var moos = 0

    override fun moo() {
        moos++
        println("Moo! $moos")
    }

    override fun moo(value: String) {
        moos += 2
        println("Moo! $moos: $value")
    }

    override fun moo(value: String, delay: Long) {
        moos += 4
        println("Moo! $moos: $value ($delay)")
        try {
            Thread.sleep(delay)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    override fun id(): Int {
        return id
    }

    override fun slow(): Float {
        println("Slowdown!!")
        try {
            Thread.sleep(2000)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        return 123.0f
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
