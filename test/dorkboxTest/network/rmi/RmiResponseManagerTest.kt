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
package dorkboxTest.network.rmi

import dorkbox.network.rmi.ResponseManager
import dorkboxTest.network.BaseTest
import kotlinx.atomicfu.AtomicInt
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.junit.Assert
import org.junit.Test

class RmiResponseManagerTest: BaseTest() {
    companion object {
        private val logger =  KotlinLogging.logger("RmiResponseManagerTest")
    }

    @Test
    fun rmiResponseRoundTrip() {
        runTest(2, 3)
        runTest(2, 30)
        runTest(2, 300)
        runTest(2, 3000)
        runTest(2, 65535)

       runTest(65535 * 2, 3)
       runTest(65535 * 2, 30)
       runTest(65535 * 2, 300)
       runTest(65535 * 2, 3000)
       runTest(65535 * 2, 65535)
    }

    @Test
    fun rmiResponseInvalidRoundTrip() {
       runTest(65535 * 2, 65535)
       runTest(65535 * 2, 1, false)
       runTest(65535 * 2, 65538, false)
    }

    @OptIn(ObsoleteCoroutinesApi::class)
    private fun runTest(totalCount: Int, responseMangerSize: Int, expectedToPass: Boolean = true) {
        val counted: AtomicInt = atomic(totalCount)
        val logger =  KotlinLogging.logger("RmiResponseManagerTest")

        try {
            val responseManager = ResponseManager(responseMangerSize)

            runBlocking {
                val actor = actor<Deferred<Int>>(Dispatchers.Default, 0) {
                    for (e in this) {
                        val await = e.await()
                        val waiterCallback = responseManager.getWaiterCallback<() -> Unit>(await, logger)
                        Assert.assertTrue(waiterCallback != null)

                        waiterCallback!!.invoke()
                    }
                }

                repeat(totalCount) {
                    async {
                        responseManager.prepWithCallback(logger) {
    //                        logger.error { "function invoked $it!" }
                            counted.decrementAndGet()
                        }
                    }.also {
                        actor.send(it)
                    }
                }

                actor.close()
                // data is fully processed once the runBlocking is exited. Before then, it is not. (the "capacity" of the actor is "always there")
            }
        } catch (e: Exception) {
            if (expectedToPass) {
                throw e
            } else {
                Assert.assertTrue(true)
            }
        }

        if (expectedToPass) {
            Assert.assertEquals(0, counted.value)
        } else {
            Assert.assertTrue(true)
        }
    }
}
