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

/**
 *
 */
interface TestCow : TestCowBase {
    fun moo()
    fun moo(value: String)
    fun mooTwo(value: String): String
    suspend fun moo(value: String, delay: Long)
    fun id(): Int
    suspend fun slow(): Float

    suspend fun withSuspend(value: String, v2: Int)
    suspend fun withSuspendAndReturn(value: String, v2: Int): Int
}
