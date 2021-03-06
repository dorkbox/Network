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

open class TestCowBaseImpl : TestCowBase {
    override fun throwException() {
        throw UnsupportedOperationException("Why would I do that?")
    }

    override suspend fun throwSuspendException(): Boolean {
        throw UnsupportedOperationException("Why would I do that on suspend?")
    }

    open fun id(): Int {
        return Int.MAX_VALUE
    }
}
